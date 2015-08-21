package com.intel.distml.platform;

import akka.actor.ActorContext;
import akka.actor.ActorRef;
import akka.pattern.Patterns;
import com.intel.distml.api.Model;
import com.intel.distml.api.Partition;
import com.intel.distml.api.PartitionInfo;
import com.intel.distml.util.Constants;
import com.intel.distml.util.KeyCollection;
import com.intel.distml.util.Logger;
import com.intel.distml.util.Matrix;
import scala.concurrent.Await;
import scala.concurrent.Future;

import java.util.LinkedList;
import java.util.List;

import static akka.dispatch.Futures.sequence;

/**
 * Created by taotao on 15-2-3.
 */
public class GeneralDataBus {

    Model model;
    ActorContext context;

    public GeneralDataBus(Model model, ActorContext context) {
        this.model = model;
        this.context = context;
    }

    public Matrix fetchFromRemote(String matrixName, KeyCollection rowKeys, KeyCollection colsKeys,
                                  PartitionInfo partitionInfo, ActorRef[] remotes) {
        log("fetchFromRemote: " + matrixName + ", " + partitionInfo);
        LinkedList<Future<Object>> responseFutures = new LinkedList<Future<Object>>();
        if ((partitionInfo == null) || (partitionInfo.type == PartitionInfo.Type.COPIED)) {
            DataBusProtocol.PartialDataRequest partialDataRequest = new DataBusProtocol.PartialDataRequest(matrixName, rowKeys, colsKeys);
            responseFutures.add(Patterns.ask(remotes[0], partialDataRequest, Constants.DATA_FUTURE_TIMEOUT));
        }
        else if (partitionInfo.type == PartitionInfo.Type.EXCLUSIVE) {
            Logger.DebugLog("remote: " + remotes[partitionInfo.exclusiveIndex], Logger.Role.DATABUS, 0);
            DataBusProtocol.PartialDataRequest partialDataRequest = new DataBusProtocol.PartialDataRequest(matrixName, rowKeys, colsKeys);
            responseFutures.add(Patterns.ask(remotes[partitionInfo.exclusiveIndex], partialDataRequest, Constants.DATA_FUTURE_TIMEOUT));
        }
        else {
            for (int serverIndex = 0; serverIndex < partitionInfo.partitions.size(); ++serverIndex) {
                Partition partition = partitionInfo.partitions.get(serverIndex);
                log("check intersect: " + partition.keys + ", " + rowKeys);
                KeyCollection keys = partition.keys.intersect(rowKeys);
                log("partial keys: " + keys.size());
                if (!keys.isEmpty()) {
                    //log("partial request: " + keys.size());
                    DataBusProtocol.PartialDataRequest partialDataRequest = new DataBusProtocol.PartialDataRequest(matrixName, keys, colsKeys);
                    responseFutures.add(Patterns.ask(remotes[serverIndex], partialDataRequest, Constants.DATA_FUTURE_TIMEOUT));
                }
            }
        }
        Future<Iterable<Object>> responsesFuture = sequence(responseFutures, context.dispatcher());

        try {
            Iterable<Object> responses = Await.result(responsesFuture, Constants.DATA_FUTURE_TIMEOUT_DURATION);
            //log("response: " + responses);
            if (responses instanceof DataBusProtocol.Data) {
                //System.out.println("data response: " + ((DataBusProtocol.Data) responses).data);
                return ((DataBusProtocol.Data) responses).data;
            }
            else {
                LinkedList<Matrix> dataList = new LinkedList<Matrix>();
                for (Object response : responses) {
                    DataBusProtocol.Data data = (DataBusProtocol.Data) response;
                    //System.out.println("data list response: " + data.data);
                    // Just in case, in fact normally the data should never be null
                    if (data.data != null) {
                        //System.out.println("add to list: " + dataList);
                        dataList.add(data.data);
                    }
                }
                Matrix m = mergeMatrices(dataList);
                //m.show();
                log("fetchFromRemote done: size=" + m.getRowKeys().size());
                return m;
            }
        } catch (Exception e) {
            e.printStackTrace();
            Logger.ErrorLog(e.toString(), Logger.Role.DATABUS, 0);
            throw new RuntimeException("no result.");
            //return null; // TODO Return null?
        }
    }

    public boolean pushToRemote(String matrixName, boolean initializeOnly, Matrix data, PartitionInfo partitionInfo, ActorRef[] remotes) {
        log("pushToRemote: " + data + ", partitionInfo=" + partitionInfo + ", " + data.getRowKeys().size());

        LinkedList<Future<Object>> responseFutures = new LinkedList<Future<Object>>();

        if ((partitionInfo == null) || (partitionInfo.type == PartitionInfo.Type.COPIED)) {
            DataBusProtocol.PushDataRequest pushRequest = new DataBusProtocol.PushDataRequest(matrixName, initializeOnly, data);
            responseFutures.add(Patterns.ask(remotes[0], pushRequest, Constants.DATA_FUTURE_TIMEOUT));
        }
        else if (partitionInfo.type == PartitionInfo.Type.EXCLUSIVE) {
            DataBusProtocol.PushDataRequest pushRequest = new DataBusProtocol.PushDataRequest(matrixName, initializeOnly, data);
            responseFutures.add(Patterns.ask(remotes[partitionInfo.exclusiveIndex], pushRequest, Constants.DATA_FUTURE_TIMEOUT));
        }
        else {
            int total = 0;
            for (int serverIndex = 0; serverIndex < partitionInfo.partitions.size(); ++serverIndex) {
                Partition partition = partitionInfo.partitions.get(serverIndex);
                Matrix part = data.subMatrix(partition.keys, KeyCollection.ALL);
                if ((part != null) && (part.getRowKeys().size() > 0)) {
                    //log("push data size " + part.getRowKeys().size() + " to " + remotes[serverIndex]);
                    total += part.getRowKeys().size();
                    DataBusProtocol.PushDataRequest pushRequest = new DataBusProtocol.PushDataRequest(matrixName, initializeOnly, part);
                    responseFutures.add(Patterns.ask(remotes[serverIndex], pushRequest, Constants.DATA_FUTURE_TIMEOUT));
                }
            }
            if (total < data.getRowKeys().size()) {
                throw new RuntimeException("not all data can be pushed.");
            }
        }
        Future<Iterable<Object>> responsesFuture = sequence(responseFutures, context.dispatcher());

        try {
            Iterable<Object> responses = Await.result(responsesFuture, Constants.DATA_FUTURE_TIMEOUT_DURATION);
            //System.out.println("response: " + responses);
            boolean result = true;
            for(Object res : responses) {
                if (!((DataBusProtocol.PushDataResponse)res).success) {
                    return false;
                }
            }
            return true;
        } catch (Exception e) {
            e.printStackTrace();
            Logger.ErrorLog(e.toString(), Logger.Role.DATABUS, 0);
            throw new RuntimeException("failed to push.");
            //return null; // TODO Return null?
        }
    }


    Matrix mergeMatrices(List<Matrix> matrices) {
        if (!matrices.isEmpty()) {
            Matrix newMatrix = matrices.get(0);
            matrices.remove(0);
            newMatrix.mergeMatrices(matrices);
            //System.out.println("merged: " + newMatrix);
            return newMatrix;
        } else {
            throw new RuntimeException("invalid result, empty list.");
        }
    }

    private void log(String msg) {
        Logger.DebugLog("GeneralDataBus", msg);
    }
}

/**
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */
package com.alibaba.rocketmq.store.ha;

import com.alibaba.rocketmq.common.ServiceThread;
import com.alibaba.rocketmq.common.constant.LoggerName;
import com.alibaba.rocketmq.remoting.common.RemotingUtil;
import com.alibaba.rocketmq.store.CommitLog.GroupCommitRequest;
import com.alibaba.rocketmq.store.DefaultMessageStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.*;
import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;


/**
 * rocketmq的主备同步是这样做的:
 * master启动以后，会启动一个ha端口， 默认10912 ， 接收slave 的连接请求；
 * slave把自己注册到Nameserver以后，会在注册结果里面拿到一个master的ha 地址，
 * 然后和master建立ha connection .基于这个ha 连接， slave每次发送8字节的slaveRequestOffset,
 * master收到这个offset,从commitlog拉一批消息， 发送sync request 给slave (数据格式：8字节commitlog offset + 4字节消息长度+commitlog列表） ，
 * slave收到消息以后，写入自己的commitlog  ,接着发送下一个slaveRequestOffset.
 *
 * @author shijia.wxr
 */
public class HAService {
    private static final Logger log = LoggerFactory.getLogger(LoggerName.StoreLoggerName);
    private final AtomicInteger connectionCount = new AtomicInteger(0);
    private final List<HAConnection> connectionList = new LinkedList<HAConnection>();
    private final AcceptSocketService acceptSocketService;
    private final DefaultMessageStore defaultMessageStore;
    private final WaitNotifyObject waitNotifyObject = new WaitNotifyObject();
    private final AtomicLong push2SlaveMaxOffset = new AtomicLong(0);
    private final GroupTransferService groupTransferService;
    private final HAClient haClient;


    public HAService(final DefaultMessageStore defaultMessageStore) throws IOException {
        this.defaultMessageStore = defaultMessageStore;
        this.acceptSocketService =
                new AcceptSocketService(defaultMessageStore.getMessageStoreConfig().getHaListenPort());
        this.groupTransferService = new GroupTransferService();
        this.haClient = new HAClient();
    }


    public void updateMasterAddress(final String newAddr) {
        if (this.haClient != null) {
            this.haClient.updateMasterAddress(newAddr);
        }
    }


    public void putRequest(final GroupCommitRequest request) {
        this.groupTransferService.putRequest(request);
    }

    /**
     *master broker的commitlog已经写到了masterPutWhere 这个位置。
     *
     * 这里判断slave OK的条件是 有slave连接到Master, 并且commitlog写入的位点和broker推送给slave并且已经被ack的位点差异 ，
     * 要小于预先设置的最大的位点差异。
     *
     * @param masterPutWhere
     * @return
     */
    public boolean isSlaveOK(final long masterPutWhere) {
        boolean result = this.connectionCount.get() > 0;
        result =
                result
                        && ((masterPutWhere - this.push2SlaveMaxOffset.get()) < this.defaultMessageStore
                            .getMessageStoreConfig().getHaSlaveFallbehindMax());
        return result;
    }

    public void notifyTransferSome(final long offset) { //slave ack的位点信息比master push 到slave的位点还要大 。
        for (long value = this.push2SlaveMaxOffset.get(); offset > value;) {
            boolean ok = this.push2SlaveMaxOffset.compareAndSet(value, offset);
            if (ok) {
                this.groupTransferService.notifyTransferSome();
                break;
            }
            else {
                value = this.push2SlaveMaxOffset.get();
            }
        }
    }


    public AtomicInteger getConnectionCount() {
        return connectionCount;
    }


    // public void notifyTransferSome() {
    // this.groupTransferService.notifyTransferSome();
    // }

    public void start() {
        this.acceptSocketService.beginAccept();
        this.acceptSocketService.start();
        this.groupTransferService.start();
        this.haClient.start();
    }


    public void addConnection(final HAConnection conn) {
        synchronized (this.connectionList) {
            this.connectionList.add(conn);
        }
    }


    public void removeConnection(final HAConnection conn) {
        synchronized (this.connectionList) {
            this.connectionList.remove(conn);
        }
    }


    public void shutdown() {
        this.haClient.shutdown();
        this.acceptSocketService.shutdown(true);
        this.destroyConnections();
        this.groupTransferService.shutdown();
    }


    public void destroyConnections() {
        synchronized (this.connectionList) {
            for (HAConnection c : this.connectionList) {
                c.shutdown();
            }

            this.connectionList.clear();
        }
    }


    public DefaultMessageStore getDefaultMessageStore() {
        return defaultMessageStore;
    }


    public WaitNotifyObject getWaitNotifyObject() {
        return waitNotifyObject;
    }

    class AcceptSocketService extends ServiceThread {
        private ServerSocketChannel serverSocketChannel;
        private Selector selector;
        private SocketAddress socketAddressListen;


        public AcceptSocketService(final int port) {
            this.socketAddressListen = new InetSocketAddress(port);
        }


        public void beginAccept() {
            try {
                this.serverSocketChannel = ServerSocketChannel.open();
                //Linux平台默认会使用epoll selector provider.
                this.selector = RemotingUtil.openSelector();
                this.serverSocketChannel.socket().setReuseAddress(true);
                this.serverSocketChannel.socket().bind(this.socketAddressListen);
                this.serverSocketChannel.configureBlocking(false);
                this.serverSocketChannel.register(this.selector, SelectionKey.OP_ACCEPT);
            }
            catch (Exception e) {
                log.error("beginAccept exception", e);
            }
        }


        @Override
        public void run() {
            log.info(this.getServiceName() + " service started");

            while (!this.isStoped()) {
                try {
                    this.selector.select(1000);
                    Set<SelectionKey> selected = this.selector.selectedKeys();
                    if (selected != null) {
                        for (SelectionKey k : selected) {
                            if ((k.readyOps() & SelectionKey.OP_ACCEPT) != 0) {
                                SocketChannel sc = ((ServerSocketChannel) k.channel()).accept();
                                if (sc != null) {
                                    HAService.log.info("HAService receive new connection, "
                                            + sc.socket().getRemoteSocketAddress());

                                    try {
                                    	//一个slave对应一个ha连结。 
                                        HAConnection conn = new HAConnection(HAService.this, sc);
                                        conn.start();
                                        HAService.this.addConnection(conn);
                                    }
                                    catch (Exception e) {
                                        log.error("new HAConnection exception", e);
                                        sc.close();
                                    }
                                }
                            }
                            else {
                                log.warn("Unexpected ops in select " + k.readyOps());
                            }
                        }

                        selected.clear();
                    }

                }
                catch (Exception e) {
                    log.error(this.getServiceName() + " service has exception.", e);
                }
            }

            log.error(this.getServiceName() + " service end");
        }


        @Override
        public String getServiceName() {
            return AcceptSocketService.class.getSimpleName();
        }
    }

    /**
     * GroupTransferService Service
     */
    class GroupTransferService extends ServiceThread {
        private final WaitNotifyObject notifyTransferObject = new WaitNotifyObject();
        private volatile List<GroupCommitRequest> requestsWrite = new ArrayList<GroupCommitRequest>();
        private volatile List<GroupCommitRequest> requestsRead = new ArrayList<GroupCommitRequest>();


        public void putRequest(final GroupCommitRequest request) {
            synchronized (this) {
                this.requestsWrite.add(request);
                if (!this.hasNotified) {
                    this.hasNotified = true;
                    this.notify();
                }
            }
        }


        public void notifyTransferSome() {
            this.notifyTransferObject.wakeup();
        }


        private void swapRequests() {
            List<GroupCommitRequest> tmp = this.requestsWrite;
            this.requestsWrite = this.requestsRead;
            this.requestsRead = tmp;
        }


        private void doWaitTransfer() {
            if (!this.requestsRead.isEmpty()) {
                for (GroupCommitRequest req : this.requestsRead) {
                    boolean transferOK = HAService.this.push2SlaveMaxOffset.get() >= req.getNextOffset();
                    for (int i = 0; !transferOK && i < 5; i++) {
                        this.notifyTransferObject.waitForRunning(1000);
                        transferOK = HAService.this.push2SlaveMaxOffset.get() >= req.getNextOffset();
                    }

                    if (!transferOK) {
                        log.warn("transfer messsage to slave timeout, " + req.getNextOffset());
                    }

                    req.wakeupCustomer(transferOK);
                }

                this.requestsRead.clear();
            }
        }


        public void run() {
            log.info(this.getServiceName() + " service started");

            while (!this.isStoped()) {
                try {
                    this.waitForRunning(0);
                    this.doWaitTransfer();
                }
                catch (Exception e) {
                    log.warn(this.getServiceName() + " service has exception. ", e);
                }
            }

            log.info(this.getServiceName() + " service end");
        }


        @Override
        protected void onWaitEnd() {
            this.swapRequests();
        }


        @Override
        public String getServiceName() {
            return GroupTransferService.class.getSimpleName();
        }
    }

    class HAClient extends ServiceThread {
        private static final int ReadMaxBufferSize = 1024 * 1024 * 4;
        private final AtomicReference<String> masterAddress = new AtomicReference<String>();
        private final ByteBuffer reportOffset = ByteBuffer.allocate(8);
        private SocketChannel socketChannel;
        private Selector selector;
        private long lastWriteTimestamp = System.currentTimeMillis();
        private long currentReportedOffset = 0;
        private int dispatchPostion = 0; //master发送给slave的sync response的字节总数
        private ByteBuffer byteBufferRead = ByteBuffer.allocate(ReadMaxBufferSize);
        private ByteBuffer byteBufferBackup = ByteBuffer.allocate(ReadMaxBufferSize);


        public HAClient() throws IOException {
            this.selector = RemotingUtil.openSelector();
        }


        public void updateMasterAddress(final String newAddr) {
            String currentAddr = this.masterAddress.get();
            if (currentAddr == null || !currentAddr.equals(newAddr)) {
                this.masterAddress.set(newAddr);
                log.info("update master address, OLD: " + currentAddr + " NEW: " + newAddr);
            }
        }


        private boolean isTimeToReportOffset() {
            long interval =
                    HAService.this.defaultMessageStore.getSystemClock().now() - this.lastWriteTimestamp;
            boolean needHeart =
                    (interval > HAService.this.defaultMessageStore.getMessageStoreConfig()
                        .getHaSendHeartbeatInterval());

            return needHeart;
        }


        private boolean reportSlaveMaxOffset(final long maxOffset) {
            this.reportOffset.position(0);
            this.reportOffset.limit(8);
            this.reportOffset.putLong(maxOffset);
            this.reportOffset.position(0);
            this.reportOffset.limit(8);

            for (int i = 0; i < 3 && this.reportOffset.hasRemaining(); i++) {
                try {
                    this.socketChannel.write(this.reportOffset);
                }
                catch (IOException e) {
                    log.error(this.getServiceName()
                            + "reportSlaveMaxOffset this.socketChannel.write exception", e);
                    return false;
                }
            }

            return !this.reportOffset.hasRemaining();
        }

        private void reallocateByteBuffer() {
            int remain = ReadMaxBufferSize - this.dispatchPostion;
            if (remain > 0) { //已经接受的字节数小于读缓冲区大小。。
                this.byteBufferRead.position(this.dispatchPostion);

                this.byteBufferBackup.position(0);
                this.byteBufferBackup.limit(ReadMaxBufferSize);
                this.byteBufferBackup.put(this.byteBufferRead);
            }

            this.swapByteBuffer();

            this.byteBufferRead.position(remain);
            this.byteBufferRead.limit(ReadMaxBufferSize);
            this.dispatchPostion = 0;
        }


        private void swapByteBuffer() {
            ByteBuffer tmp = this.byteBufferRead;
            this.byteBufferRead = this.byteBufferBackup;
            this.byteBufferBackup = tmp;
        }


        private boolean processReadEvent() {
            int readSizeZeroTimes = 0;
            while (this.byteBufferRead.hasRemaining()) {
                try {
                    int readSize = this.socketChannel.read(this.byteBufferRead);
                    if (readSize > 0) {
                        lastWriteTimestamp = HAService.this.defaultMessageStore.getSystemClock().now();
                        readSizeZeroTimes = 0;
                        boolean result = this.dispatchReadRequest();
                        if (!result) {
                            log.error("HAClient, dispatchReadRequest error");
                            return false;
                        }
                    }
                    else if (readSize == 0) {
                        if (++readSizeZeroTimes >= 3) {
                            break;
                        }
                    }
                    else {
                        // TODO ERROR
                        log.info("HAClient, processReadEvent read socket < 0");
                        return false;
                    }
                }
                catch (IOException e) {
                    log.info("HAClient, processReadEvent read socket exception", e);
                    return false;
                }
            }

            return true;
        }


        /**
         * 读取master发送给slave的commitlog sync response ： 8 + 4 + commitlog的字节数组
         * @return
         */
        private boolean dispatchReadRequest() {
            final int MSG_HEADER_SIZE = 8 + 4; // phyoffset + size
            int readSocketPos = this.byteBufferRead.position();

            while (true) {
                int diff = this.byteBufferRead.position() - this.dispatchPostion;
                if (diff >= MSG_HEADER_SIZE) { //收满了12个字节
                	//master推送给slave的commitlog的offset  .
                    long masterPhyOffset = this.byteBufferRead.getLong(this.dispatchPostion);
                    //推送过来的消息体的大小。
                    int bodySize = this.byteBufferRead.getInt(this.dispatchPostion + 8);

                    long slavePhyOffset = HAService.this.defaultMessageStore.getMaxPhyOffset();

                    if (slavePhyOffset != 0) {
                        if (slavePhyOffset != masterPhyOffset) {
                            log.error("master pushed offset not equal the max phy offset in slave, SLAVE: "
                                    + slavePhyOffset + " MASTER: " + masterPhyOffset);
                            return false;
                        }
                    }

                    if (diff >= (MSG_HEADER_SIZE + bodySize)) {
                        byte[] bodyData = new byte[bodySize];
                        this.byteBufferRead.position(this.dispatchPostion + MSG_HEADER_SIZE);
                        this.byteBufferRead.get(bodyData);
                        //slave把commitlog写入本地。
                        HAService.this.defaultMessageStore.appendToCommitLog(masterPhyOffset, bodyData);

                        this.byteBufferRead.position(readSocketPos);
                        this.dispatchPostion += MSG_HEADER_SIZE + bodySize;

                        if (!reportSlaveMaxOffsetPlus()) {
                            return false;
                        }

                        continue;
                    }
                }

                if (!this.byteBufferRead.hasRemaining()) {
                    this.reallocateByteBuffer();
                }

                break;
            }

            return true;
        }


        private boolean reportSlaveMaxOffsetPlus() {
            boolean result = true;
            long currentPhyOffset = HAService.this.defaultMessageStore.getMaxPhyOffset();
            if (currentPhyOffset > this.currentReportedOffset) {
                this.currentReportedOffset = currentPhyOffset;
                result = this.reportSlaveMaxOffset(this.currentReportedOffset);
                if (!result) {
                    this.closeMaster();
                    log.error("HAClient, reportSlaveMaxOffset error, " + this.currentReportedOffset);
                }
            }

            return result;
        }


        private boolean connectMaster() throws ClosedChannelException {
            if (null == socketChannel) {
                //BrokerController 的603 行， slave在把自己注册到NameServer的时候， 返回的注册结果里面会有master 的ha地址。
                //在那里会更新haService的master的地址
                String addr = this.masterAddress.get();
                if (addr != null) {

                    SocketAddress socketAddress = RemotingUtil.string2SocketAddress(addr);
                    if (socketAddress != null) {
                        //连上HaService启动的Ha端口, 看HAService的212行, Master的HAService会接收HaClient的连接请求，
                        //建立一个HaConnection .
                        this.socketChannel = RemotingUtil.connect(socketAddress);
                        if (this.socketChannel != null) {
                            this.socketChannel.register(this.selector, SelectionKey.OP_READ);
                        }
                    }
                }
                //连上master以后，slave需要report给master的commitlog位点默认是 最后一个Mapfile写入的位点。
                this.currentReportedOffset = HAService.this.defaultMessageStore.getMaxPhyOffset();

                this.lastWriteTimestamp = System.currentTimeMillis();
            }

            return this.socketChannel != null;
        }


        private void closeMaster() {
            if (null != this.socketChannel) {
                try {

                    SelectionKey sk = this.socketChannel.keyFor(this.selector);
                    if (sk != null) {
                        sk.cancel();
                    }

                    this.socketChannel.close();

                    this.socketChannel = null;
                }
                catch (IOException e) {
                    log.warn("closeMaster exception. ", e);
                }

                this.lastWriteTimestamp = 0;
                this.dispatchPostion = 0;

                this.byteBufferBackup.position(0);
                this.byteBufferBackup.limit(ReadMaxBufferSize);

                this.byteBufferRead.position(0);
                this.byteBufferRead.limit(ReadMaxBufferSize);
            }
        }


        @Override
        public void run() {
            log.info(this.getServiceName() + " service started");

            while (!this.isStoped()) {
                try {
                    if (this.connectMaster()) {
                        if (this.isTimeToReportOffset()) { //到了默认5秒的Ha心跳时间， 写8个字节currentReportedOffset 给Master。
                            boolean result = this.reportSlaveMaxOffset(this.currentReportedOffset);
                            if (!result) {
                                this.closeMaster();
                            }
                        }

                        this.selector.select(1000);

                        boolean ok = this.processReadEvent();
                        if (!ok) {
                            this.closeMaster();
                        }

                        if (!reportSlaveMaxOffsetPlus()) {
                            continue;
                        }


                        //默认超过20s的housekeeping时间，还是没有收到master发送过来的数据， 则关闭和master的套接字通道。
                        long interval =
                                HAService.this.getDefaultMessageStore().getSystemClock().now()
                                        - this.lastWriteTimestamp;
                        if (interval > HAService.this.getDefaultMessageStore().getMessageStoreConfig()
                            .getHaHousekeepingInterval()) {
                            log.warn("HAClient, housekeeping, found this connection[" + this.masterAddress
                                    + "] expired, " + interval);
                            this.closeMaster();
                            log.warn("HAClient, master not response some time, so close connection");
                        }
                    }
                    else {
                        this.waitForRunning(1000 * 5);
                    }
                }
                catch (Exception e) {
                    log.warn(this.getServiceName() + " service has exception. ", e);
                    this.waitForRunning(1000 * 5);
                }
            }

            log.info(this.getServiceName() + " service end");
        }

        @Override
        public String getServiceName() {
            return HAClient.class.getSimpleName();
        }
    }


    public AtomicLong getPush2SlaveMaxOffset() {
        return push2SlaveMaxOffset;
    }
}

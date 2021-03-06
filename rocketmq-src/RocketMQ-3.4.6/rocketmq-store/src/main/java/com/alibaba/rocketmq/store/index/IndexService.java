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
package com.alibaba.rocketmq.store.index;

import com.alibaba.rocketmq.common.UtilAll;
import com.alibaba.rocketmq.common.constant.LoggerName;
import com.alibaba.rocketmq.common.message.MessageConst;
import com.alibaba.rocketmq.common.sysflag.MessageSysFlag;
import com.alibaba.rocketmq.store.DefaultMessageStore;
import com.alibaba.rocketmq.store.DispatchRequest;
import com.alibaba.rocketmq.store.config.StorePathConfigHelper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;


/**
 * [root@s10-2-X-5 index]# ls
 20170227205337348  20170303181716054  20170309172222236  20170315084023929  20170321101102147  20170326130945963  20170331124017050  20170405125237370
 20170228170936582  20170307071446472  20170312100610618  20170317214604854  20170323200605634  20170329060659180  20170402182834879  20170407110209375
 [root@s10-2-30-5 index]# du -sh *
 401M    20170227205337348
 401M    20170228170936582
 401M    20170303181716054
 401M    20170307071446472
 401M    20170309172222236
 401M    20170312100610618
 401M    20170315084023929
 401M    20170317214604854
 401M    20170321101102147
 401M    20170323200605634
 401M    20170326130945963
 401M    20170329060659180
 401M    20170331124017050
 401M    20170402182834879
 401M    20170405125237370
 117M    20170407110209375
 [root@s10-2-X-5 index]#
 * @author shijia.wxr
 * IndexService用于创建索引文件集合，当用户想要查询某个topic下某个key的消息时，能够快速响应  主要用于查询消息用的，根据topic+key获取消息,
 * 见开发手册:7.3.2  按照Message Key 查询消息
 *
 * // 异步线程分发 commitlog 文件中的消息到 consumeQueue 或者分发到 indexService 见 ReputMessageService
 */
public class IndexService {
    private static final Logger log = LoggerFactory.getLogger(LoggerName.StoreLoggerName);
    private final DefaultMessageStore defaultMessageStore;
    private final int hashSlotNum;
    private final int indexNum;
    private final String storePath; // /root/store/index
    private final ArrayList<IndexFile> indexFileList = new ArrayList<IndexFile>();
    private final ReadWriteLock readWriteLock = new ReentrantReadWriteLock();


    public IndexService(final DefaultMessageStore store) {
        this.defaultMessageStore = store;
        this.hashSlotNum = store.getMessageStoreConfig().getMaxHashSlotNum();
        this.indexNum = store.getMessageStoreConfig().getMaxIndexNum();
        this.storePath =
                StorePathConfigHelper.getStorePathIndex(store.getMessageStoreConfig().getStorePathRootDir());
    }

    /*
    * [root@s10-2-30-5 index]# ls
20170227205337348  20170303181716054  20170309172222236  20170315084023929  20170321101102147  20170326130945963  20170331124017050  20170405125237370
20170228170936582  20170307071446472  20170312100610618  20170317214604854  20170323200605634  20170329060659180  20170402182834879  20170407110209375
[root@s10-2-30-5 index]# du -sh *
401M    20170227205337348
401M    20170228170936582
401M    20170303181716054
401M    20170307071446472
401M    20170309172222236
401M    20170312100610618
401M    20170315084023929
401M    20170317214604854
401M    20170321101102147
401M    20170323200605634
401M    20170326130945963
401M    20170329060659180
401M    20170331124017050
401M    20170402182834879
401M    20170405125237370
117M    20170407110209375
[root@s10-2-30-5 index]#
    * */
    //加载 /root/store/index目录下面的文件到
    public boolean load(final boolean lastExitOK) {
        File dir = new File(this.storePath);
        File[] files = dir.listFiles();
        if (files != null) {
            // ascending order
            Arrays.sort(files);
            for (File file : files) {
                try {
                    IndexFile f = new IndexFile(file.getPath(), this.hashSlotNum, this.indexNum, 0, 0);
                    f.load();

                    if (!lastExitOK) {
                        if (f.getEndTimestamp() > this.defaultMessageStore.getStoreCheckpoint()
                            .getIndexMsgTimestamp()) {
                            f.destroy(0);
                            continue;
                        }
                    }

                    log.info("load index file OK, " + f.getFileName());
                    this.indexFileList.add(f);
                }
                catch (IOException e) {
                    log.error("load file " + file + " error", e);
                    return false;
                }
            }
        }

        return true;
    }

    public void deleteExpiredFile(long offset) {
        Object[] files = null;
        try {
            this.readWriteLock.readLock().lock();
            if (this.indexFileList.isEmpty()) {
                return;
            }

            long endPhyOffset = this.indexFileList.get(0).getEndPhyOffset();
            if (endPhyOffset < offset) {
                files = this.indexFileList.toArray();
            }
        }
        catch (Exception e) {
            log.error("destroy exception", e);
        }
        finally {
            this.readWriteLock.readLock().unlock();
        }

        if (files != null) {
            List<IndexFile> fileList = new ArrayList<IndexFile>();
            for (int i = 0; i < (files.length - 1); i++) {
                IndexFile f = (IndexFile) files[i];
                if (f.getEndPhyOffset() < offset) {
                    fileList.add(f);
                }
                else {
                    break;
                }
            }

            this.deleteExpiredFile(fileList);
        }
    }

    private void deleteExpiredFile(List<IndexFile> files) {
        if (!files.isEmpty()) {
            try {
                this.readWriteLock.writeLock().lock();
                for (IndexFile file : files) {
                    boolean destroyed = file.destroy(3000);
                    destroyed = destroyed && this.indexFileList.remove(file);
                    if (!destroyed) {
                        log.error("deleteExpiredFile remove failed.");
                        break;
                    }
                }
            }
            catch (Exception e) {
                log.error("deleteExpiredFile has exception.", e);
            }
            finally {
                this.readWriteLock.writeLock().unlock();
            }
        }
    }


    public void destroy() {
        try {
            this.readWriteLock.readLock().lock();
            for (IndexFile f : this.indexFileList) {
                f.destroy(1000 * 3);
            }
            this.indexFileList.clear();
        }
        catch (Exception e) {
            log.error("destroy exception", e);
        }
        finally {
            this.readWriteLock.readLock().unlock();
        }
    }


    public QueryOffsetResult queryOffset(String topic, String key, int maxNum, long begin, long end) {
        List<Long> phyOffsets = new ArrayList<Long>(maxNum);
        long indexLastUpdateTimestamp = 0;
        long indexLastUpdatePhyoffset = 0;
        maxNum = Math.min(maxNum, this.defaultMessageStore.getMessageStoreConfig().getMaxMsgsNumBatch());
        try {
            this.readWriteLock.readLock().lock();
            if (!this.indexFileList.isEmpty()) {
                for (int i = this.indexFileList.size(); i > 0; i--) {
                    IndexFile f = this.indexFileList.get(i - 1);
                    boolean lastFile = (i == this.indexFileList.size());
                    if (lastFile) {
                        indexLastUpdateTimestamp = f.getEndTimestamp();
                        indexLastUpdatePhyoffset = f.getEndPhyOffset();
                    }

                    if (f.isTimeMatched(begin, end)) {
                        f.selectPhyOffset(phyOffsets, this.buildKey(topic, key), maxNum, begin, end, lastFile);
                    }

                    if (f.getBeginTimestamp() < begin) {
                        break;
                    }

                    if (phyOffsets.size() >= maxNum) {
                        break;
                    }
                }
            }
        }
        catch (Exception e) {
            log.error("queryMsg exception", e);
        }
        finally {
            this.readWriteLock.readLock().unlock();
        }

        return new QueryOffsetResult(phyOffsets, indexLastUpdateTimestamp, indexLastUpdatePhyoffset);
    }


    private String buildKey(final String topic, final String key) {
        return topic + "#" + key;
    }


    public void buildIndex(DispatchRequest req) {
        boolean breakdown = false;
        IndexFile indexFile = retryGetAndCreateIndexFile();
        if (indexFile != null) {
            long endPhyOffset = indexFile.getEndPhyOffset();
            DispatchRequest msg = req;
            String topic = msg.getTopic();
            String keys = msg.getKeys();
            if (msg.getCommitLogOffset() < endPhyOffset) {
                return;
            }

            final int tranType = MessageSysFlag.getTransactionValue(msg.getSysFlag());
            switch (tranType) {
            case MessageSysFlag.TransactionNotType:
            case MessageSysFlag.TransactionPreparedType:
                break;
            case MessageSysFlag.TransactionCommitType:
            case MessageSysFlag.TransactionRollbackType:
                return;
            }

            if (keys != null && keys.length() > 0) {
                String[] keyset = keys.split(MessageConst.KEY_SEPARATOR);
                for (String key : keyset) {
                    if (key.length() > 0) {
                        for (boolean ok =
                                indexFile.putKey(buildKey(topic, key), msg.getCommitLogOffset(),
                                    msg.getStoreTimestamp()); !ok;) {
                            log.warn("index file full, so create another one, " + indexFile.getFileName());
                            indexFile = retryGetAndCreateIndexFile();
                            if (null == indexFile) {
                                breakdown = true;
                                return;
                            }

                            ok =
                                    indexFile.putKey(buildKey(topic, key), msg.getCommitLogOffset(),
                                        msg.getStoreTimestamp());
                        }
                    }
                }
            }
        }
        else {
            log.error("build index error, stop building index");
        }
    }


    public IndexFile retryGetAndCreateIndexFile() {
        IndexFile indexFile = null;

        for (int times = 0; null == indexFile && times < 3; times++) {
            indexFile = this.getAndCreateLastIndexFile();
            if (null != indexFile)
                break;

            try {
                log.error("try to create index file, " + times + " times");
                Thread.sleep(1000);
            }
            catch (InterruptedException e) {
                e.printStackTrace();
            }
        }

        if (null == indexFile) {
            this.defaultMessageStore.getAccessRights().makeIndexFileError();
            log.error("mark index file can not build flag");
        }

        return indexFile;
    }

    public IndexFile getAndCreateLastIndexFile() {
        IndexFile indexFile = null;
        IndexFile prevIndexFile = null;
        long lastUpdateEndPhyOffset = 0;
        long lastUpdateIndexTimestamp = 0;
        {
            this.readWriteLock.readLock().lock();
            if (!this.indexFileList.isEmpty()) {
                IndexFile tmp = this.indexFileList.get(this.indexFileList.size() - 1);
                if (!tmp.isWriteFull()) {
                    indexFile = tmp;
                }
                else {
                    lastUpdateEndPhyOffset = tmp.getEndPhyOffset();
                    lastUpdateIndexTimestamp = tmp.getEndTimestamp();
                    prevIndexFile = tmp;
                }
            }

            this.readWriteLock.readLock().unlock();
        }

        if (indexFile == null) {
            try {
                String fileName =
                        this.storePath + File.separator
                                + UtilAll.timeMillisToHumanString(System.currentTimeMillis());
                indexFile =
                        new IndexFile(fileName, this.hashSlotNum, this.indexNum, lastUpdateEndPhyOffset,
                            lastUpdateIndexTimestamp);
                this.readWriteLock.writeLock().lock();
                this.indexFileList.add(indexFile);
            }
            catch (Exception e) {
                log.error("getLastIndexFile exception ", e);
            }
            finally {
                this.readWriteLock.writeLock().unlock();
            }

            if (indexFile != null) {
                final IndexFile flushThisFile = prevIndexFile;
                Thread flushThread = new Thread(new Runnable() {
                    @Override
                    public void run() {
                        IndexService.this.flush(flushThisFile);
                    }
                }, "FlushIndexFileThread");

                flushThread.setDaemon(true);
                flushThread.start();
            }
        }

        return indexFile;
    }


    public void flush(final IndexFile f) {
        if (null == f)
            return;

        long indexMsgTimestamp = 0;

        if (f.isWriteFull()) {
            indexMsgTimestamp = f.getEndTimestamp();
        }

        f.flush();

        if (indexMsgTimestamp > 0) {
            this.defaultMessageStore.getStoreCheckpoint().setIndexMsgTimestamp(indexMsgTimestamp);
            this.defaultMessageStore.getStoreCheckpoint().flush();
        }
    }


    public void start() {

    }


    public void shutdown() {

    }
}

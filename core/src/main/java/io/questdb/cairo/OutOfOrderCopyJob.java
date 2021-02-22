/*******************************************************************************
 *     ___                  _   ____  ____
 *    / _ \ _   _  ___  ___| |_|  _ \| __ )
 *   | | | | | | |/ _ \/ __| __| | | |  _ \
 *   | |_| | |_| |  __/\__ \ |_| |_| | |_) |
 *    \__\_\\__,_|\___||___/\__|____/|____/
 *
 *  Copyright (c) 2014-2019 Appsicle
 *  Copyright (c) 2019-2020 QuestDB
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *
 ******************************************************************************/

package io.questdb.cairo;

import io.questdb.MessageBus;
import io.questdb.log.Log;
import io.questdb.log.LogFactory;
import io.questdb.mp.*;
import io.questdb.std.FilesFacade;
import io.questdb.std.Unsafe;
import io.questdb.std.Vect;
import io.questdb.std.str.Path;
import io.questdb.tasks.OutOfOrderCopyTask;
import io.questdb.tasks.OutOfOrderUpdPartitionSizeTask;

import java.util.concurrent.atomic.AtomicInteger;

import static io.questdb.cairo.TableWriter.*;

public class OutOfOrderCopyJob extends AbstractQueueConsumerJob<OutOfOrderCopyTask> {
    private static final Log LOG = LogFactory.getLog(OutOfOrderCopyJob.class);
    private final CairoConfiguration configuration;
    private final RingQueue<OutOfOrderUpdPartitionSizeTask> updPartitionSizeQueue;
    private final MPSequence updPartitionSizePubSeq;

    public OutOfOrderCopyJob(MessageBus messageBus) {
        super(messageBus.getOutOfOrderCopyQueue(), messageBus.getOutOfOrderCopySubSequence());
        this.configuration = messageBus.getConfiguration();
        this.updPartitionSizeQueue = messageBus.getOutOfOrderUpdPartitionSizeQueue();
        this.updPartitionSizePubSeq = messageBus.getOutOfOrderUpdPartitionSizePubSequence();
    }

    public static void copy(
            CairoConfiguration configuration,
            RingQueue<OutOfOrderUpdPartitionSizeTask> updPartitionSizeTaskQueue,
            MPSequence updPartitionSizePubSeq,
            AtomicInteger columnCounter,
            AtomicInteger partCounter,
            FilesFacade ff,
            CharSequence pathToTable,
            int columnType,
            int blockType,
            long timestampMergeIndexAddr,
            long srcDataFixFd,
            long srcDataFixAddr,
            long srcDataFixOffset,
            long srcDataFixSize,
            long srcDataVarFd,
            long srcDataVarAddr,
            long srcDataVarOffset,
            long srcDataVarSize,
            long srcDataTopOffset,
            long srcDataLo,
            long srcDataHi,
            long srcDataMax,
            long tableFloorOfMaxTimestamp,
            long dataTimestampHi,
            long srcOooFixAddr,
            long srcOooFixSize,
            long srcOooVarAddr,
            long srcOooVarSize,
            long srcOooLo,
            long srcOooHi,
            long srcOooMax,
            long srcOooPartitionLo,
            long srcOooPartitionHi,
            long oooTimestampMin,
            long oooTimestampMax,
            long oooTimestampHi,
            long dstFixFd,
            long dstFixAddr,
            long dstFixOffset,
            long dstFixSize,
            long dstVarFd,
            long dstVarAddr,
            long dstVarOffset,
            long dstVarSize,
            long dstKFd,
            long dstVFd,
            long dstIndexOffset,
            boolean isIndexed,
            long srcTimestampFd,
            long srcTimestampAddr,
            long srcTimestampSize,
            boolean partitionMutates,
            TableWriter tableWriter,
            SOUnboundedCountDownLatch doneLatch
    ) {
        boolean success = false;
        try {
            switch (blockType) {
                case OO_BLOCK_MERGE:
                    if (srcDataFixOffset == 0 && srcDataTopOffset == 0) {
                        oooMergeCopy(
                                columnType,
                                timestampMergeIndexAddr,
                                srcDataFixAddr,
                                srcDataVarAddr,
                                srcDataLo,
                                srcDataHi,
                                srcOooFixAddr,
                                srcOooVarAddr,
                                srcOooLo,
                                srcOooHi,
                                dstFixAddr + dstFixOffset,
                                dstVarAddr,
                                dstVarOffset
                        );
                    } else {
                        oooMergeCopyWithOffset(
                                columnType,
                                timestampMergeIndexAddr,
                                srcDataFixAddr,
                                // this is a hack, when we have column top we can have only of of the two:
                                // srcDataFixOffset, when we had to shift data to back fill nulls or
                                // srcDataTopOffset - if we kept the column top
                                // when one value is present the other will be 0
                                srcDataFixOffset - srcDataTopOffset,
                                srcDataVarAddr,
                                srcDataVarOffset,
                                srcDataLo,
                                srcDataHi,
                                srcOooFixAddr,
                                srcOooVarAddr,
                                srcOooLo,
                                srcOooHi,
                                dstFixAddr + dstFixOffset,
                                dstVarAddr,
                                dstVarOffset
                        );
                    }
                    break;
                case OO_BLOCK_OO:
                    oooCopyOOO(
                            columnType,
                            srcOooFixAddr,
                            srcOooFixSize,
                            srcOooVarAddr,
                            srcOooVarSize,
                            srcOooLo,
                            srcOooHi,
                            dstFixAddr + dstFixOffset,
                            dstVarAddr,
                            dstVarOffset
                    );
                    break;
                case OO_BLOCK_DATA:
                    oooCopyData(
                            columnType,
                            srcDataFixAddr + srcDataFixOffset,
                            srcDataFixSize - srcDataFixOffset,
                            srcDataVarAddr + srcDataVarOffset,
                            srcDataVarSize - srcDataVarOffset,
                            srcDataLo,
                            srcDataHi,
                            dstFixAddr + dstFixOffset,
                            dstVarAddr,
                            dstVarOffset
                    );
                    break;
                default:
                    break;
            }
            success = true;
        } finally {
            // decrement part counter and if we are the last task - perform final steps
            if (partCounter.decrementAndGet() == 0) {
                // todo: pool indexer
                if (isIndexed) {
                    if (success) {
                        // dstKFd & dstVFd are closed by the indexer
                        updateIndex(configuration, dstFixAddr, dstFixSize, dstKFd, dstVFd, dstIndexOffset);
                    } else {
                        ff.close(dstKFd);
                        ff.close(dstVFd);
                    }
                }

                // unmap memory
                unmapAndClose(ff, srcDataFixFd, srcDataFixAddr, srcDataFixSize);
                unmapAndClose(ff, srcDataVarFd, srcDataVarAddr, srcDataVarSize);
                unmapAndClose(ff, dstFixFd, dstFixAddr, dstFixSize);
                unmapAndClose(ff, dstVarFd, dstVarAddr, dstVarSize);

                final int columnsRemaining = columnCounter.decrementAndGet();
                LOG.debug().$("organic [columnsRemaining=").$(columnsRemaining).$(']').$();
                if (columnsRemaining == 0) {
                    unmap(ff, srcTimestampAddr, srcTimestampSize);
                    try {
                        touchPartition(
                                ff,
                                updPartitionSizeTaskQueue,
                                updPartitionSizePubSeq,
                                pathToTable,
                                srcOooPartitionLo,
                                srcOooPartitionHi,
                                oooTimestampMin,
                                oooTimestampMax,
                                oooTimestampHi,
                                srcOooMax,
                                srcDataMax,
                                tableFloorOfMaxTimestamp,
                                dataTimestampHi,
                                srcTimestampFd,
                                partitionMutates,
                                tableWriter,
                                success
                        );
                    } finally {
                        if (timestampMergeIndexAddr != 0) {
                            Vect.freeMergedIndex(timestampMergeIndexAddr);
                        }
                        doneLatch.countDown();
                    }
                }
            }
        }
    }

    public static void copy(
            CairoConfiguration configuration,
            RingQueue<OutOfOrderUpdPartitionSizeTask> updPartitionSizeQueue,
            MPSequence updPartitionSizePubSeq,
            OutOfOrderCopyTask task,
            long cursor,
            Sequence subSeq
    ) {
        final AtomicInteger columnCounter = task.getColumnCounter();
        final AtomicInteger partCounter = task.getPartCounter();
        final FilesFacade ff = task.getFf();
        final CharSequence pathToTable = task.getPathToTable();
        final int columnType = task.getColumnType();
        final int blockType = task.getBlockType();
        final long timestampMergeIndexAddr = task.getTimestampMergeIndexAddr();
        final long srcDataFixFd = task.getSrcDataFixFd();
        final long srcDataFixAddr = task.getSrcDataFixAddr();
        final long srcDataFixOffset = task.getSrcDataFixOffset();
        final long srcDataFixSize = task.getSrcDataFixSize();
        final long srcDataVarFd = task.getSrcDataVarFd();
        final long srcDataVarAddr = task.getSrcDataVarAddr();
        final long srcDataVarOffset = task.getSrcDataVarOffset();
        final long srcDataVarSize = task.getSrcDataVarSize();
        final long srcDataTopOffset = task.getSrcDataTopOffset();
        final long srcDataLo = task.getSrcDataLo();
        final long srcDataMax = task.getSrcDataMax();
        final long srcDataHi = task.getSrcDataHi();
        final long tableFloorOfMaxTimestamp = task.getTableFloorOfMaxTimestamp();
        final long dataTimestampHi = task.getDataTimestampHi();
        final long srcOooFixAddr = task.getSrcOooFixAddr();
        final long srcOooFixSize = task.getSrcOooFixSize();
        final long srcOooVarAddr = task.getSrcOooVarAddr();
        final long srcOooVarSize = task.getSrcOooVarSize();
        final long srcOooLo = task.getSrcOooLo();
        final long srcOooHi = task.getSrcOooHi();
        final long srcOooMax = task.getSrcOooMax();
        final long srcOooPartitionLo = task.getSrcOooPartitionLo();
        final long srcOooPartitionHi = task.getSrcOooPartitionHi();
        final long oooTimestampMin = task.getOooTimestampMin();
        final long oooTimestampMax = task.getOooTimestampMax();
        final long oooTimestampHi = task.getOooTimestampHi();
        final long dstFixFd = task.getDstFixFd();
        final long dstFixAddr = task.getDstFixAddr();
        final long dstFixOffset = task.getDstFixOffset();
        final long dstFixSize = task.getDstFixSize();
        final long dstVarFd = task.getDstVarFd();
        final long dstVarAddr = task.getDstVarAddr();
        final long dstVarOffset = task.getDstVarOffset();
        final long dstVarSize = task.getDstVarSize();
        final long dstKFd = task.getDstKFd();
        final long dskVFd = task.getDstVFd();
        final long dstIndexOffset = task.getDstIndexOffset();
        final boolean isIndexed = task.isIndexed();
        final long srcTimestampFd = task.getSrcTimestampFd();
        final long srcTimestampAddr = task.getSrcTimestampAddr();
        final long srcTimestampSize = task.getSrcTimestampSize();
        final boolean partitionMutates = task.isPartitionMutates();
        final TableWriter tableWriter = task.getTableWriter();
        final SOUnboundedCountDownLatch doneLatch = task.getDoneLatch();

        subSeq.done(cursor);

        copy(
                configuration,
                updPartitionSizeQueue,
                updPartitionSizePubSeq,
                columnCounter,
                partCounter,
                ff,
                pathToTable,
                columnType,
                blockType,
                timestampMergeIndexAddr,
                srcDataFixFd,
                srcDataFixAddr,
                srcDataFixOffset,
                srcDataFixSize,
                srcDataVarFd,
                srcDataVarAddr,
                srcDataVarOffset,
                srcDataVarSize,
                srcDataTopOffset,
                srcDataLo,
                srcDataHi,
                srcDataMax,
                tableFloorOfMaxTimestamp,
                dataTimestampHi,
                srcOooFixAddr,
                srcOooFixSize,
                srcOooVarAddr,
                srcOooVarSize,
                srcOooLo,
                srcOooHi,
                srcOooMax,
                srcOooPartitionLo,
                srcOooPartitionHi,
                oooTimestampMin,
                oooTimestampMax,
                oooTimestampHi,
                dstFixFd,
                dstFixAddr,
                dstFixOffset,
                dstFixSize,
                dstVarFd,
                dstVarAddr,
                dstVarOffset,
                dstVarSize,
                dstKFd,
                dskVFd,
                dstIndexOffset,
                isIndexed,
                srcTimestampFd,
                srcTimestampAddr,
                srcTimestampSize,
                partitionMutates,
                tableWriter,
                doneLatch
        );
    }

    static void copyIdle(
            AtomicInteger columnCounter,
            AtomicInteger partCounter,
            FilesFacade ff,
            long timestampMergeIndexAddr,
            long srcDataFixFd,
            long srcDataFixAddr,
            long srcDataFixSize,
            long srcDataVarFd,
            long srcDataVarAddr,
            long srcDataVarSize,
            long dstFixFd,
            long dstFixAddr,
            long dstFixSize,
            long dstVarFd,
            long dstVarAddr,
            long dstVarSize,
            long srcTimestampFd,
            long srcTimestampAddr,
            long srcTimestampSize,
            long dstKFd,
            long dstVFd,
            TableWriter tableWriter,
            SOUnboundedCountDownLatch doneLatch
    ) {
        if (partCounter.decrementAndGet() == 0) {
            // unmap memory
            copyIdleQuick(
                    columnCounter,
                    ff,
                    timestampMergeIndexAddr,
                    srcDataFixFd,
                    srcDataFixAddr,
                    srcDataFixSize,
                    srcDataVarFd,
                    srcDataVarAddr,
                    srcDataVarSize,
                    dstFixFd,
                    dstFixAddr,
                    dstFixSize,
                    dstVarFd,
                    dstVarAddr,
                    dstVarSize,
                    srcTimestampFd,
                    srcTimestampAddr,
                    srcTimestampSize,
                    dstKFd,
                    dstVFd,
                    tableWriter,
                    doneLatch
            );
        }
    }

    static void copyIdleQuick(
            AtomicInteger columnCounter,
            FilesFacade ff,
            long timestampMergeIndexAddr,
            long srcDataFixFd,
            long srcDataFixAddr,
            long srcDataFixSize,
            long srcDataVarFd,
            long srcDataVarAddr,
            long srcDataVarSize,
            long dstFixFd,
            long dstFixAddr,
            long dstFixSize,
            long dstVarFd,
            long dstVarAddr,
            long dstVarSize,
            long srcTimestampFd,
            long srcTimestampAddr,
            long srcTimestampSize,
            long dstKFd,
            long dstVFd,
            TableWriter tableWriter,
            SOUnboundedCountDownLatch doneLatch
    ) {
        unmapAndClose(ff, srcDataFixFd, srcDataFixAddr, srcDataFixSize);
        unmapAndClose(ff, srcDataVarFd, srcDataVarAddr, srcDataVarSize);
        unmapAndClose(ff, dstFixFd, dstFixAddr, dstFixSize);
        unmapAndClose(ff, dstVarFd, dstVarAddr, dstVarSize);
        close(ff, dstKFd);
        close(ff, dstVFd);

        closeColumnIdle(
                columnCounter,
                ff,
                timestampMergeIndexAddr,
                srcTimestampFd,
                srcTimestampAddr,
                srcTimestampSize,
                tableWriter,
                doneLatch
        );
    }

    static void closeColumnIdle(AtomicInteger columnCounter, FilesFacade ff, long timestampMergeIndexAddr, long srcTimestampFd, long srcTimestampAddr, long srcTimestampSize, TableWriter tableWriter, SOUnboundedCountDownLatch doneLatch) {
        final int columnsRemaining = columnCounter.decrementAndGet();
        LOG.debug().$("idle [columnsRemaining=").$(columnsRemaining).$(']').$();
        if (columnsRemaining == 0) {
            unmap(ff, srcTimestampAddr, srcTimestampSize);
            close(ff, srcTimestampFd);
            if (timestampMergeIndexAddr != 0) {
                Vect.freeMergedIndex(timestampMergeIndexAddr);
            }
            tableWriter.bumpPartitionUpdateCount();
            doneLatch.countDown();
        }
    }

    private static void touchPartition(
            FilesFacade ff,
            RingQueue<OutOfOrderUpdPartitionSizeTask> updPartitionSizeQueue,
            MPSequence updPartitionPubSeq,
            CharSequence pathToTable,
            long srcOooPartitionLo,
            long srcOooPartitionHi,
            long oooTimestampMin,
            long oooTimestampMax,
            long oooTimestampHi, // local (partition bound) maximum of OOO timestamp
            long srcOooMax,
            long srcDataMax,
            long tableFloorOfMaxTimestamp,
            long dataTimestampHi,
            long srcTimestampFd,
            boolean partitionMutates,
            TableWriter tableWriter,
            boolean success
    ) {
        try {
            if (partitionMutates && success) {
                if (srcTimestampFd < 0) {
                    // srcTimestampFd negative indicates that we are reusing existing file descriptor
                    // as opposed to opening file by name. This also indicated that "this" partition
                    // is, or used to be, active for the writer. So we have to close existing files so thatT
                    // rename on Windows does not fall flat.
                    tableWriter.closeActivePartition();
                } else {
                    // this timestamp column was opened by file name
                    // so we can close it as not needed (and to enable table rename on Windows)
                    ff.close(srcTimestampFd);
                }

                renamePartition(
                        ff,
                        pathToTable,
                        oooTimestampHi,
                        tableWriter
                );

            } else if (srcTimestampFd > 0) {
                ff.close(srcTimestampFd);
            }
        } catch (Throwable e) {
            tableWriter.bumpOooErrorCount();
            throw e;
        } finally {
            notifyWriter(
                    updPartitionSizeQueue,
                    updPartitionPubSeq,
                    srcOooPartitionLo,
                    srcOooPartitionHi,
                    oooTimestampMin,
                    oooTimestampMax,
                    oooTimestampHi,
                    srcOooMax,
                    srcDataMax,
                    tableFloorOfMaxTimestamp,
                    dataTimestampHi,
                    tableWriter
            );
        }
    }

    static void notifyWriter(
            RingQueue<OutOfOrderUpdPartitionSizeTask> updPartitionSizeQueue,
            MPSequence updPartitionPubSeq,
            long srcOooPartitionLo,
            long srcOooPartitionHi,
            long oooTimestampMin,
            long oooTimestampMax,
            long oooTimestampHi,
            long srcOooMax,
            long srcDataMax,
            long tableFloorOfMaxTimestamp,
            long dataTimestampHi,
            TableWriter tableWriter
    ) {
        final long cursor = updPartitionPubSeq.next();
        if (cursor > -1) {
            publishUpdPartitionSizeTaskHarmonized(
                    updPartitionSizeQueue,
                    updPartitionPubSeq,
                    cursor,
                    srcOooPartitionLo,
                    srcOooPartitionHi,
                    oooTimestampHi,
                    srcDataMax,
                    dataTimestampHi
            );
        } else {
            publishUpdPartitionSizeTaskContended(
                    updPartitionSizeQueue,
                    updPartitionPubSeq,
                    cursor,
                    srcOooPartitionLo,
                    srcOooPartitionHi,
                    oooTimestampMin,
                    oooTimestampMax,
                    oooTimestampHi,
                    srcOooMax,
                    srcDataMax,
                    tableFloorOfMaxTimestamp,
                    dataTimestampHi,
                    tableWriter
            );
        }
    }

    private static void publishUpdPartitionSizeTaskContended(
            RingQueue<OutOfOrderUpdPartitionSizeTask> updPartitionSizeQueue,
            MPSequence updPartitionPubSeq,
            long cursor,
            long srcOooPartitionLo,
            long srcOooPartitionHi,
            long oooTimestampMin,
            long oooTimestampMax,
            long oooTimestampHi,
            long srcOooMax,
            long srcDataMax,
            long tableFloorOfMaxTimestamp,
            long dataTimestampHi,
            TableWriter tableWriter
    ) {
        while (cursor == -2) {
            cursor = updPartitionPubSeq.next();
        }

        if (cursor > -1) {
            publishUpdPartitionSizeTaskHarmonized(
                    updPartitionSizeQueue,
                    updPartitionPubSeq,
                    cursor,
                    srcOooPartitionLo,
                    srcOooPartitionHi,
                    oooTimestampHi,
                    srcDataMax,
                    dataTimestampHi
            );
        } else {
            tableWriter.oooUpdatePartitionSizeSynchronized(
                    oooTimestampMin,
                    oooTimestampMax,
                    oooTimestampHi,
                    srcOooPartitionLo,
                    srcOooPartitionHi,
                    tableFloorOfMaxTimestamp,
                    dataTimestampHi,
                    srcOooMax,
                    srcDataMax
            );
        }
    }

    private static void publishUpdPartitionSizeTaskHarmonized(
            RingQueue<OutOfOrderUpdPartitionSizeTask> updPartitionSizeQueue,
            MPSequence updPartitionPubSeq, long cursor,
            long srcOooPartitionLo,
            long srcOooPartitionHi,
            long oooTimestampHi,
            long srcDataMax,
            long dataTimestampHi
    ) {
        final OutOfOrderUpdPartitionSizeTask task = updPartitionSizeQueue.get(cursor);
        task.of(
                oooTimestampHi,
                srcOooPartitionLo,
                srcOooPartitionHi,
                srcDataMax,
                dataTimestampHi
        );
        updPartitionPubSeq.done(cursor);
    }

    private static void renamePartition(FilesFacade ff, CharSequence pathToTable, long oooTimestampHi, TableWriter tableWriter) {
        final long txn = tableWriter.getTxn();
        final Path path = Path.getThreadLocal(pathToTable);
        TableUtils.setPathForPartition(path, tableWriter.getPartitionBy(), oooTimestampHi);
        final int plen = path.length();
        path.$();
        final Path other = Path.getThreadLocal2(path);
        TableUtils.oldPartitionName(other, txn);
        if (ff.rename(path, other.$())) {
            TableUtils.newPartitionName(other.trimTo(plen), txn);
            if (ff.rename(other.$(), path)) {
                return;
            }
            throw CairoException.instance(ff.errno())
                    .put("could not rename [from=").put(other)
                    .put(", to=").put(path).put(']');
        } else {
            throw CairoException.instance(ff.errno())
                    .put("could not rename [from=").put(path)
                    .put(", to=").put(other).put(']');
        }
    }

    private static void oooCopyData(
            int columnType,
            long srcFixAddr,
            long srcFixSize,
            long srcVarAddr,
            long srcVarSize,
            long srcLo,
            long srcHi,
            long dstFixAddr,
            long dstVarAddr,
            long dstVarOffset
    ) {
        switch (columnType) {
            case ColumnType.STRING:
            case ColumnType.BINARY:
                oooCopyVarSizeCol(
                        srcFixAddr,
                        srcFixSize,
                        srcVarAddr,
                        srcVarSize,
                        srcLo,
                        srcHi,
                        dstFixAddr,
                        dstVarAddr,
                        dstVarOffset
                );
                break;
            default:
                oooCopyFixedSizeCol(
                        srcFixAddr,
                        srcLo,
                        srcHi,
                        dstFixAddr,
                        ColumnType.pow2SizeOf(Math.abs(columnType))
                );
                break;
        }
    }

    private static void oooCopyFixedSizeCol(long src, long srcLo, long srcHi, long dst, final int shl) {
        Unsafe.getUnsafe().copyMemory(src + (srcLo << shl), dst, (srcHi - srcLo + 1) << shl);
    }

    // todo: move to C++
    private static void oooCopyIndex(long mergeIndexAddr, long mergeIndexSize, long dstAddr) {
        for (long l = 0; l < mergeIndexSize; l++) {
            Unsafe.getUnsafe().putLong(dstAddr + l * Long.BYTES, getTimestampIndexValue(mergeIndexAddr, l));
        }
    }

    private static void oooCopyOOO(
            int columnType,
            long srcOooFixAddr,
            long srcOooFixSize,
            long srcOooVarAddr,
            long srcOooVarSize,
            long srcOooLo,
            long srcOooHi,
            long dstFixAddr,
            long dstVarAddr,
            long dstVarOffset
    ) {
        switch (columnType) {
            case ColumnType.STRING:
            case ColumnType.BINARY:
                // we can find out the edge of string column in one of two ways
                // 1. if srcOooHi is at the limit of the page - we need to copy the whole page of strings
                // 2  if there are more items behind srcOooHi we can get offset of srcOooHi+1
                oooCopyVarSizeCol(
                        srcOooFixAddr,
                        srcOooFixSize,
                        srcOooVarAddr,
                        srcOooVarSize,
                        srcOooLo,
                        srcOooHi,
                        dstFixAddr,
                        dstVarAddr,
                        dstVarOffset
                );
                break;
            case ColumnType.BOOLEAN:
            case ColumnType.BYTE:
                oooCopyFixedSizeCol(srcOooFixAddr, srcOooLo, srcOooHi, dstFixAddr, 0);
                break;
            case ColumnType.CHAR:
            case ColumnType.SHORT:
                oooCopyFixedSizeCol(srcOooFixAddr, srcOooLo, srcOooHi, dstFixAddr, 1);
                break;
            case ColumnType.INT:
            case ColumnType.FLOAT:
            case ColumnType.SYMBOL:
                oooCopyFixedSizeCol(srcOooFixAddr, srcOooLo, srcOooHi, dstFixAddr, 2);
                break;
            case ColumnType.LONG:
            case ColumnType.DATE:
            case ColumnType.DOUBLE:
            case ColumnType.TIMESTAMP:
                oooCopyFixedSizeCol(srcOooFixAddr, srcOooLo, srcOooHi, dstFixAddr, 3);
                break;
            case -ColumnType.TIMESTAMP:
                TableUtils.copyFromTimestampIndex(srcOooFixAddr, srcOooLo, srcOooHi, dstFixAddr);
                break;
            default:
                break;
        }
    }

    private static void oooCopyVarSizeCol(
            long srcFixAddr,
            long srcFixSize,
            long srcVarAddr,
            long srcVarSize,
            long srcLo,
            long srcHi,
            long dstFixAddr,
            long dstVarAddr,
            long dstVarOffset

    ) {
        final long lo = TableUtils.findVarOffset(srcFixAddr, srcLo, srcHi, srcVarSize);
        final long hi;
        if (srcHi + 1 == srcFixSize / Long.BYTES) {
            hi = srcVarSize;
        } else {
            hi = TableUtils.findVarOffset(srcFixAddr, srcHi + 1, srcFixSize / Long.BYTES, srcVarSize);
        }
        // copy this before it changes
        final long dest = dstVarAddr + dstVarOffset;
        final long len = hi - lo;
        Unsafe.getUnsafe().copyMemory(srcVarAddr + lo, dest, len);
        if (lo == dstVarOffset) {
            oooCopyFixedSizeCol(srcFixAddr, srcLo, srcHi, dstFixAddr, 3);
        } else {
            TableUtils.shiftCopyFixedSizeColumnData(lo - dstVarOffset, srcFixAddr, srcLo, srcHi, dstFixAddr);
        }
    }

    private static void oooMergeCopy(
            int columnType,
            long mergeIndexAddr,
            long srcDataFixAddr,
            long srcDataVarAddr,
            long srcDataLo,
            long srcDataHi,
            long srcOooFixAddr,
            long srcOooVarAddr,
            long srcOooLo,
            long srcOooHi,
            long dstFixAddr,
            long dstVarAddr,
            long dstVarOffset
    ) {
        final long rowCount = srcOooHi - srcOooLo + 1 + srcDataHi - srcDataLo + 1;
        switch (columnType) {
            case ColumnType.BOOLEAN:
            case ColumnType.BYTE:
                Vect.mergeShuffle8Bit(srcDataFixAddr, srcOooFixAddr, dstFixAddr, mergeIndexAddr, rowCount);
                break;
            case ColumnType.SHORT:
            case ColumnType.CHAR:
                Vect.mergeShuffle16Bit(srcDataFixAddr, srcOooFixAddr, dstFixAddr, mergeIndexAddr, rowCount);
                break;
            case ColumnType.STRING:
                Vect.oooMergeCopyStrColumn(
                        mergeIndexAddr,
                        rowCount,
                        srcDataFixAddr,
                        srcDataVarAddr,
                        srcOooFixAddr,
                        srcOooVarAddr,
                        dstFixAddr,
                        dstVarAddr,
                        dstVarOffset
                );
                break;
            case ColumnType.BINARY:
                Vect.oooMergeCopyBinColumn(
                        mergeIndexAddr,
                        rowCount,
                        srcDataFixAddr,
                        srcDataVarAddr,
                        srcOooFixAddr,
                        srcOooVarAddr,
                        dstFixAddr,
                        dstVarAddr,
                        dstVarOffset
                );
                break;
            case ColumnType.INT:
            case ColumnType.FLOAT:
            case ColumnType.SYMBOL:
                Vect.mergeShuffle32Bit(srcDataFixAddr, srcOooFixAddr, dstFixAddr, mergeIndexAddr, rowCount);
                break;
            case ColumnType.DOUBLE:
            case ColumnType.LONG:
            case ColumnType.DATE:
            case ColumnType.TIMESTAMP:
                Vect.mergeShuffle64Bit(srcDataFixAddr, srcOooFixAddr, dstFixAddr, mergeIndexAddr, rowCount);
                break;
            case -ColumnType.TIMESTAMP:
                oooCopyIndex(mergeIndexAddr, rowCount, dstFixAddr);
                break;
            default:
                break;
        }
    }

    private static void oooMergeCopyWithOffset(
            int columnType,
            long timestampMergeIndexAddr,
            long srcDataFixAddr,
            long srcDataFixOffset,
            long srcDataVarAddr,
            long srcDataVarOffset,
            long srcDataLo,
            long srcDataHi,
            long srcOooFixAddr,
            long srcOooVarAddr,
            long srcOooLo,
            long srcOooHi,
            long dstFixAddr,
            long dstVarAddr,
            long dstVarOffset
    ) {
        final long rowCount = srcOooHi - srcOooLo + 1 + srcDataHi - srcDataLo + 1;
        switch (columnType) {
            case ColumnType.BOOLEAN:
            case ColumnType.BYTE:
                Vect.mergeShuffleWithTop8Bit(srcDataFixAddr, srcOooFixAddr, dstFixAddr, timestampMergeIndexAddr, rowCount, srcDataFixOffset);
                break;
            case ColumnType.SHORT:
            case ColumnType.CHAR:
                Vect.mergeShuffleWithTop16Bit(srcDataFixAddr, srcOooFixAddr, dstFixAddr, timestampMergeIndexAddr, rowCount, srcDataFixOffset);
                break;
            case ColumnType.STRING:
                Vect.oooMergeCopyStrColumnWithTop(
                        timestampMergeIndexAddr,
                        rowCount,
                        srcDataFixAddr,
                        srcDataFixOffset,
                        srcDataVarAddr + srcDataVarOffset,
                        srcOooFixAddr,
                        srcOooVarAddr,
                        dstFixAddr,
                        dstVarAddr,
                        dstVarOffset
                );
                break;
            case ColumnType.BINARY:
                Vect.oooMergeCopyBinColumnWithTop(
                        timestampMergeIndexAddr,
                        rowCount,
                        srcDataFixAddr,
                        srcDataFixOffset,
                        srcDataVarAddr + srcDataVarOffset,
                        srcOooFixAddr,
                        srcOooVarAddr,
                        dstFixAddr,
                        dstVarAddr,
                        dstVarOffset
                );
                break;
            case ColumnType.INT:
            case ColumnType.FLOAT:
            case ColumnType.SYMBOL:
                Vect.mergeShuffleWithTop32Bit(srcDataFixAddr, srcOooFixAddr, dstFixAddr, timestampMergeIndexAddr, rowCount, srcDataFixOffset);
                break;
            case ColumnType.DOUBLE:
            case ColumnType.LONG:
            case ColumnType.DATE:
            case ColumnType.TIMESTAMP:
                Vect.mergeShuffleWithTop64Bit(srcDataFixAddr, srcOooFixAddr, dstFixAddr, timestampMergeIndexAddr, rowCount, srcDataFixOffset);
                break;
            default:
                break;
        }
    }

    private static void unmapAndClose(FilesFacade ff, long dstFixFd, long dstFixAddr, long dstFixSize) {
        unmap(ff, dstFixAddr, dstFixSize);
        close(ff, dstFixFd);
    }

    private static void unmap(FilesFacade ff, long addr, long size) {
        if (addr != 0 && size != 0) {
            ff.munmap(addr, size);
        }
    }

    private static void close(FilesFacade ff, long fd) {
        if (fd > 0) {
            LOG.debug().$("closed [fd=").$(fd).$(']').$();
            ff.close(fd);
        }
    }

    private static void updateIndex(
            CairoConfiguration configuration,
            long dstFixAddr,
            long dstFixSize,
            long dstKFd,
            long dskVFd,
            long dstIndexOffset
    ) {
        try (BitmapIndexWriter w = new BitmapIndexWriter()) {
            long row = dstIndexOffset / Integer.BYTES;
            w.of(configuration, dstKFd, dskVFd, row == 0);
            final long count = dstFixSize / Integer.BYTES;
            for (; row < count; row++) {
                assert row * Integer.BYTES < dstFixSize;
                w.add(TableUtils.toIndexKey(Unsafe.getUnsafe().getInt(dstFixAddr + row * Integer.BYTES)), row);
            }
        }
    }

    private void copy(OutOfOrderCopyTask task, long cursor, Sequence subSeq) {
        copy(
                configuration,
                updPartitionSizeQueue,
                updPartitionSizePubSeq,
                task,
                cursor,
                subSeq
        );
    }

    @Override
    protected boolean doRun(int workerId, long cursor) {
        copy(queue.get(cursor), cursor, subSeq);
        return true;
    }
}
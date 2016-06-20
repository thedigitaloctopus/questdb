/*******************************************************************************
 *    ___                  _   ____  ____
 *   / _ \ _   _  ___  ___| |_|  _ \| __ )
 *  | | | | | | |/ _ \/ __| __| | | |  _ \
 *  | |_| | |_| |  __/\__ \ |_| |_| | |_) |
 *   \__\_\\__,_|\___||___/\__|____/|____/
 *
 * Copyright (C) 2014-2016 Appsicle
 *
 * This program is free software: you can redistribute it and/or  modify
 * it under the terms of the GNU Affero General Public License, version 3,
 * as published by the Free Software Foundation.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 *
 ******************************************************************************/

package com.questdb.ql.impl.analytic.prev;

import com.questdb.ex.JournalRuntimeException;
import com.questdb.misc.Unsafe;
import com.questdb.ql.Record;
import com.questdb.ql.ops.VirtualColumn;

import java.io.Closeable;
import java.io.IOException;

public class PrevValueNonPartAnalyticFunction extends AbstractPrevValueAnalyticFunction implements Closeable {
    private final long prevPtr;
    private boolean firstPass = true;

    public PrevValueNonPartAnalyticFunction(VirtualColumn valueColumn) {
        super(valueColumn);
        this.prevPtr = Unsafe.getUnsafe().allocateMemory(8);
    }

    @Override
    public void close() throws IOException {
        if (closed) {
            return;
        }
        super.close();
        Unsafe.getUnsafe().freeMemory(prevPtr);
    }

    @Override
    public void reset() {
        super.reset();
        firstPass = true;
    }

    @Override
    public void scroll(Record record) {
        if (firstPass) {
            nextNull = true;
            firstPass = false;
        } else {
            if (nextNull) {
                nextNull = false;
            }
            Unsafe.getUnsafe().putLong(bufPtr, Unsafe.getUnsafe().getLong(prevPtr));
        }

        switch (valueColumn.getType()) {
            case BOOLEAN:
                Unsafe.getUnsafe().putByte(prevPtr, (byte) (valueColumn.getBool(record) ? 1 : 0));
                break;
            case BYTE:
                Unsafe.getUnsafe().putByte(prevPtr, valueColumn.get(record));
                break;
            case DOUBLE:
                Unsafe.getUnsafe().putDouble(prevPtr, valueColumn.getDouble(record));
                break;
            case FLOAT:
                Unsafe.getUnsafe().putFloat(prevPtr, valueColumn.getFloat(record));
                break;
            case SYMBOL:
            case INT:
                Unsafe.getUnsafe().putInt(prevPtr, valueColumn.getInt(record));
                break;
            case LONG:
            case DATE:
                Unsafe.getUnsafe().putLong(prevPtr, valueColumn.getLong(record));
                break;
            case SHORT:
                Unsafe.getUnsafe().putShort(prevPtr, valueColumn.getShort(record));
                break;
            default:
                throw new JournalRuntimeException("Unsupported type: " + valueColumn.getType());
        }
    }
}
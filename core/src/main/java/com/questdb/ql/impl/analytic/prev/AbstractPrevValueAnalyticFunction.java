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

import com.questdb.factory.configuration.RecordColumnMetadata;
import com.questdb.misc.Numbers;
import com.questdb.misc.Unsafe;
import com.questdb.ql.RecordCursor;
import com.questdb.ql.impl.analytic.AnalyticFunction;
import com.questdb.ql.ops.VirtualColumn;
import com.questdb.std.CharSink;
import com.questdb.std.DirectInputStream;
import com.questdb.store.ColumnType;
import com.questdb.store.SymbolTable;

import java.io.Closeable;
import java.io.IOException;
import java.io.OutputStream;

public abstract class AbstractPrevValueAnalyticFunction implements AnalyticFunction, Closeable {
    protected final long bufPtr;
    protected final VirtualColumn valueColumn;
    protected boolean nextNull = true;
    protected boolean closed = false;

    public AbstractPrevValueAnalyticFunction(VirtualColumn valueColumn) {
        this.valueColumn = valueColumn;
        // buffer where "current" value is kept
        this.bufPtr = Unsafe.getUnsafe().allocateMemory(8);
    }

    @Override
    public void close() throws IOException {
        if (closed) {
            return;
        }
        Unsafe.getUnsafe().freeMemory(bufPtr);
        closed = true;
    }

    @Override
    public byte get() {
        return nextNull ? 0 : Unsafe.getUnsafe().getByte(bufPtr);
    }

    @Override
    public void getBin(OutputStream s) {
        throw new UnsupportedOperationException();
    }

    @Override
    public DirectInputStream getBin() {
        throw new UnsupportedOperationException();
    }

    @Override
    public long getBinLen() {
        throw new UnsupportedOperationException();
    }

    @Override
    public boolean getBool() {
        return !nextNull && Unsafe.getUnsafe().getByte(bufPtr) == 1;
    }

    @Override
    public long getDate() {
        return getLong();
    }

    @Override
    public double getDouble() {
        return nextNull ? Double.NaN : Unsafe.getUnsafe().getDouble(bufPtr);
    }

    @Override
    public float getFloat() {
        return nextNull ? Float.NaN : Unsafe.getUnsafe().getFloat(bufPtr);
    }

    @Override
    public CharSequence getFlyweightStr() {
        throw new UnsupportedOperationException();
    }

    @Override
    public CharSequence getFlyweightStrB() {
        throw new UnsupportedOperationException();
    }

    @Override
    public int getInt() {
        return nextNull ? (valueColumn.getType() == ColumnType.SYMBOL ? SymbolTable.VALUE_IS_NULL : Numbers.INT_NaN) : Unsafe.getUnsafe().getInt(bufPtr);
    }

    @Override
    public long getLong() {
        return nextNull ? Numbers.LONG_NaN : Unsafe.getUnsafe().getLong(bufPtr);
    }

    @Override
    public RecordColumnMetadata getMetadata() {
        return valueColumn;
    }

    @Override
    public short getShort() {
        return nextNull ? 0 : (short) Unsafe.getUnsafe().getInt(bufPtr);
    }

    @Override
    public void getStr(CharSink sink) {
        throw new UnsupportedOperationException();
    }

    @Override
    public CharSequence getStr() {
        throw new UnsupportedOperationException();
    }

    @Override
    public int getStrLen() {
        throw new UnsupportedOperationException();
    }

    @Override
    public String getSym() {
        return nextNull ? null : valueColumn.getSymbolTable().value(getInt());
    }

    @Override
    public SymbolTable getSymbolTable() {
        return valueColumn.getSymbolTable();
    }

    @Override
    public void prepare(RecordCursor cursor) {
        valueColumn.prepare(cursor.getStorageFacade());
    }

    @Override
    public void reset() {
        nextNull = true;
    }
}
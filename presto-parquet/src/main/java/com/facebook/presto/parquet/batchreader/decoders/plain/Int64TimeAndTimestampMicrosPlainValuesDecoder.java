/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.facebook.presto.parquet.batchreader.decoders.plain;

import com.facebook.presto.parquet.batchreader.BytesUtils;
import com.facebook.presto.parquet.batchreader.decoders.ValuesDecoder.Int64TimeAndTimestampMicrosValuesDecoder;
import org.openjdk.jol.info.ClassLayout;

import static com.facebook.presto.common.type.DateTimeEncoding.packDateTimeWithZone;
import static com.facebook.presto.common.type.TimeZoneKey.UTC_KEY;
import static com.google.common.base.Preconditions.checkArgument;
import static io.airlift.slice.SizeOf.sizeOf;
import static java.util.concurrent.TimeUnit.MICROSECONDS;

public class Int64TimeAndTimestampMicrosPlainValuesDecoder
        implements Int64TimeAndTimestampMicrosValuesDecoder
{
    private static final int INSTANCE_SIZE = ClassLayout.parseClass(Int64TimeAndTimestampMicrosPlainValuesDecoder.class).instanceSize();

    private final byte[] byteBuffer;
    private final int bufferEnd;

    private int bufferOffset;

    private final PackFunction packFunction;

    public Int64TimeAndTimestampMicrosPlainValuesDecoder(byte[] byteBuffer, int bufferOffset, int length)
    {
        this(byteBuffer, bufferOffset, length, false);
    }

    public Int64TimeAndTimestampMicrosPlainValuesDecoder(byte[] byteBuffer, int bufferOffset, int length, boolean withTimezone)
    {
        this.byteBuffer = byteBuffer;
        this.bufferOffset = bufferOffset;
        this.bufferEnd = bufferOffset + length;
        this.packFunction = withTimezone ? millis -> packDateTimeWithZone(millis, UTC_KEY) : millis -> millis;
    }

    @Override
    public void readNext(long[] values, int offset, int length)
    {
        checkArgument(bufferOffset + length * 8 <= bufferEnd, "End of stream: invalid read request");
        checkArgument(length >= 0 && offset >= 0, "invalid read request: offset %s, length", offset, length);

        final int endOffset = offset + length;
        final byte[] localByteBuffer = byteBuffer;
        int localBufferOffset = bufferOffset;

        while (offset < endOffset) {
            long valueMillis = MICROSECONDS.toMillis(BytesUtils.getLong(localByteBuffer, localBufferOffset));
            values[offset++] = packFunction.pack(valueMillis);
            localBufferOffset += 8;
        }
        bufferOffset = localBufferOffset;
    }

    @Override
    public void skip(int length)
    {
        checkArgument(bufferOffset + length * 8 <= bufferEnd, "End of stream: invalid read request");
        checkArgument(length >= 0, "invalid length %s", length);
        bufferOffset += length * 8;
    }

    @Override
    public long getRetainedSizeInBytes()
    {
        return INSTANCE_SIZE + sizeOf(byteBuffer);
    }
}

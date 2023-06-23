// Copyright 2020-2023 The Defold Foundation
// Copyright 2014-2020 King
// Copyright 2009-2014 Ragnar Svensson, Christian Murray
// Licensed under the Defold License version 1.0 (the "License"); you may not use
// this file except in compliance with the License.
//
// You may obtain a copy of the License, together with FAQs at
// https://www.defold.com/license
//
// Unless required by applicable law or agreed to in writing, software distributed
// under the License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR
// CONDITIONS OF ANY KIND, either express or implied. See the License for the
// specific language governing permissions and limitations under the License.

package com.dynamo.bob.pipeline;

import java.util.List;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import com.dynamo.bob.CompileExceptionError;
import com.dynamo.bob.util.MurmurHash;
import com.dynamo.graphics.proto.Graphics.VertexAttribute;

import com.google.protobuf.ByteString;

public class GraphicsUtil {

    public static VertexAttribute getAttributeByName(List<VertexAttribute> materialAttributes, String attributeName)
    {
        for (VertexAttribute attr : materialAttributes) {
            if (attr.getName().equals(attributeName)) {
                return attr;
            }
        }
        return null;
    }

	private static void validateAttribute(VertexAttribute attr, VertexAttribute.DataType dataType) throws CompileExceptionError
    {
        if (dataType == VertexAttribute.DataType.TYPE_BYTE && !(attr.hasBinaryValues() || attr.hasIntValues()))
        {
            throw new CompileExceptionError(
                String.format("Invalid vertex attribute configuration for attribute '%s', data type is %s but neither byte_values nor int_values has been set.",
                    attr.getName(), dataType));
        }
        else if (dataType == VertexAttribute.DataType.TYPE_UNSIGNED_BYTE && !(attr.hasBinaryValues() || attr.hasUintValues()))
        {
            throw new CompileExceptionError(
                String.format("Invalid vertex attribute configuration for attribute '%s', data type is %s but neither byte_values nor uint_values has been set.",
                    attr.getName(), dataType));
        }
        else if ((dataType == VertexAttribute.DataType.TYPE_SHORT || dataType == VertexAttribute.DataType.TYPE_INT) && !attr.hasIntValues())
        {
            throw new CompileExceptionError(
                String.format("Invalid vertex attribute configuration for attribute '%s', data type is %s but int_values has not been set.",
                    attr.getName(), dataType));
        }
        else if ((dataType == VertexAttribute.DataType.TYPE_UNSIGNED_SHORT || dataType == VertexAttribute.DataType.TYPE_UNSIGNED_INT) && !attr.hasUintValues())
        {
            throw new CompileExceptionError(
                String.format("Invalid vertex attribute configuration for attribute '%s', data type is %s but uint_values has not been set.",
                attr.getName(), dataType));
        }
        else if (dataType == VertexAttribute.DataType.TYPE_FLOAT && !attr.hasFloatValues())
        {
            throw new CompileExceptionError(
                String.format("Invalid vertex attribute configuration for attribute '%s', data type is %s but float_values has not been set.",
                    attr.getName(), dataType));
        }
    }

    private static void putFloatList(ByteBuffer buffer, List<Float> values) {
        buffer.asFloatBuffer();
        for (int i = 0 ; i < values.size(); i++) {
            float v = (float) values.get(i);
            buffer.putFloat(v);
        }
    }

    private static void putIntList(ByteBuffer buffer, List<Integer> values) {
        buffer.asIntBuffer();
        for (int i = 0 ; i < values.size(); i++) {
            int v = (int) values.get(i);
            buffer.putInt(v);
        }
    }

    private static void putShortList(ByteBuffer buffer, List<Integer> values) {
    	buffer.asShortBuffer();
        for (int i = 0 ; i < values.size(); i++) {
            short v = (short) (int) values.get(i);
            buffer.putShort(v);
        }
    }

    private static void putByteList(ByteBuffer buffer, List<Integer> values) {
        for (int i = 0 ; i < values.size(); i++) {
            char v = (char) (int) values.get(i);
            buffer.put( (byte) v);
        }
    }

    // JG: Maybe shouldn't be here but in a more non-pipeline-util-esque location
    public static ByteBuffer newByteBuffer(int byteCount) {
        ByteBuffer bb = ByteBuffer.allocateDirect(byteCount);
        return bb.order(ByteOrder.LITTLE_ENDIAN);
    }

    public static VertexAttribute buildVertexAttribute(VertexAttribute attr, VertexAttribute.DataType dataType) throws CompileExceptionError {
        VertexAttribute.Builder attributeBuilder = VertexAttribute.newBuilder(attr);

        validateAttribute(attr, dataType);

        if (dataType == VertexAttribute.DataType.TYPE_BYTE && !attr.hasBinaryValues())
        {
            List<Integer> values = attr.getIntValues().getVList();
            ByteBuffer buffer = newByteBuffer(values.size());
            putByteList(buffer, values);
            buffer.rewind();
            attributeBuilder.setBinaryValues(ByteString.copyFrom(buffer));
        }
        else if (dataType == VertexAttribute.DataType.TYPE_UNSIGNED_BYTE && !attr.hasBinaryValues())
        {
        	List<Integer> values = attr.getUintValues().getVList();
        	ByteBuffer buffer = newByteBuffer(values.size());
        	putByteList(buffer, values);
        	buffer.rewind();
            attributeBuilder.setBinaryValues(ByteString.copyFrom(buffer));
        }
        else if (dataType == VertexAttribute.DataType.TYPE_SHORT)
        {
        	List<Integer> values = attr.getIntValues().getVList();
        	ByteBuffer buffer = newByteBuffer(values.size() * 2);
        	putShortList(buffer, values);
        	buffer.rewind();
            attributeBuilder.setBinaryValues(ByteString.copyFrom(buffer));
        }
        else if (dataType == VertexAttribute.DataType.TYPE_UNSIGNED_SHORT)
        {
            List<Integer> values = attr.getUintValues().getVList();
            ByteBuffer buffer = newByteBuffer(values.size() * 2);
            putShortList(buffer, values);
            buffer.rewind();
            attributeBuilder.setBinaryValues(ByteString.copyFrom(buffer));
        }
        else if (dataType == VertexAttribute.DataType.TYPE_FLOAT)
        {
            List<Float> values = attr.getFloatValues().getVList();
            ByteBuffer buffer = newByteBuffer(values.size() * 4);
            putFloatList(buffer, values);
            buffer.rewind();
            attributeBuilder.setBinaryValues(ByteString.copyFrom(buffer));
        }
        else if (dataType == VertexAttribute.DataType.TYPE_INT)
        {
            List<Integer> values = attr.getIntValues().getVList();
            ByteBuffer buffer = newByteBuffer(values.size() * 4);
            putIntList(buffer, values);
            buffer.rewind();
            attributeBuilder.setBinaryValues(ByteString.copyFrom(buffer));
        }
        else if (dataType == VertexAttribute.DataType.TYPE_UNSIGNED_INT)
        {
            List<Integer> values = attr.getUintValues().getVList();
            ByteBuffer buffer = newByteBuffer(values.size() * 4);
            putIntList(buffer, values);
            buffer.rewind();
            attributeBuilder.setBinaryValues(ByteString.copyFrom(buffer));
        }

        attributeBuilder.setNameHash(MurmurHash.hash64(attr.getName()));
        return attributeBuilder.build();
    }
}

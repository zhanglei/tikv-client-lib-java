/*
 * Copyright 2017 PingCAP, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.pingcap.tikv.types;

import static com.pingcap.tikv.codec.Codec.isNullFlag;

import com.google.common.collect.ImmutableList;
import com.pingcap.tikv.codec.Codec;
import com.pingcap.tikv.codec.CodecDataInput;
import com.pingcap.tikv.codec.CodecDataOutput;
import com.pingcap.tikv.meta.Collation;
import com.pingcap.tikv.meta.TiColumnInfo;
import com.pingcap.tikv.row.Row;
import java.io.Serializable;
import java.util.List;

/** Base Type for encoding and decoding TiDB row information. */
public abstract class DataType implements Serializable {

  // Flag Information for strict mysql type
  private static final int NotNullFlag = 1; /* Field can't be NULL */
  private static final int PriKeyFlag = 2; /* Field is part of a primary key */
  private static final int UniqueKeyFlag = 4; /* Field is part of a unique key */
  private static final int MultipleKeyFlag = 8; /* Field is part of a key */
  private static final int BlobFlag = 16; /* Field is a blob */
  private static final int UnsignedFlag = 32; /* Field is unsigned */
  private static final int ZerofillFlag = 64; /* Field is zerofill */
  private static final int BinaryFlag = 128; /* Field is binary   */
  private static final int EnumFlag = 256; /* Field is an enum */
  private static final int AutoIncrementFlag = 512; /* Field is an auto increment field */
  private static final int TimestampFlag = 1024; /* Field is a timestamp */
  private static final int SetFlag = 2048; /* Field is a set */
  private static final int NoDefaultValueFlag = 4096; /* Field doesn't have a default value */
  private static final int OnUpdateNowFlag = 8192; /* Field is set to NOW on UPDATE */
  private static final int NumFlag = 32768; /* Field is a num (for clients) */
  private static final int PartKeyFlag = 16384; /* Intern: Part of some keys */
  private static final int GroupFlag = 32768; /* Intern: Group field */
  private static final int BinCmpFlag = 131072; /* Intern: Used by sql_yacc */

  public enum EncodeType {
    KEY,
    VALUE
  }

  public static final int UNSPECIFIED_LEN = -1;

  // MySQL type
  protected MySQLType tp;
  // Not Encode/Decode flag, this is used to strict mysql type
  // such as not null, timestamp
  protected int flag;
  protected int decimal;
  protected int collation;
  protected long length;
  private List<String> elems;

  protected DataType(TiColumnInfo.InternalTypeHolder holder) {
    this.tp = MySQLType.fromTypeCode(holder.getTp());
    this.flag = holder.getFlag();
    this.length = holder.getFlen();
    this.decimal = holder.getDecimal();
    this.collation = Collation.translate(holder.getCollate());
    this.elems = holder.getElems() == null ? ImmutableList.of() : holder.getElems();
  }

  protected DataType(MySQLType type) {
    this.tp = type;
    this.flag = 0;
    this.elems = ImmutableList.of();
    this.length = UNSPECIFIED_LEN;
    this.decimal = UNSPECIFIED_LEN;
    this.collation = Collation.DEF_COLLATION_CODE;
  }

  protected void decodeValueNoNullToRow(Row row, int pos, Object value) {
    row.set(pos, this, value);
  }

  protected abstract Object decodeNotNull(int flag, CodecDataInput cdi);

  /**
   * decode value from row which is nothing.
   *
   * @param cdi source of data.
   */
  public Object decode(CodecDataInput cdi) {
    int flag = cdi.readUnsignedByte();
    if (isNullFlag(flag)) {
      return null;
    }
    return decodeNotNull(flag, cdi);
  }

  public void decodeValueToRow(CodecDataInput cdi, Row row, int pos) {
    int flag = cdi.readUnsignedByte();
    if (isNullFlag(flag)) {
      row.setNull(pos);
    } else {
      decodeValueNoNullToRow(row, pos, decodeNotNull(flag, cdi));
    }
  }

  public static void encodeIndexMaxValue(CodecDataOutput cdo) {
    cdo.writeByte(Codec.MAX_FLAG);
  }

  public static void encodeNull(CodecDataOutput cdo) {
    cdo.writeByte(Codec.NULL_FLAG);
  }

  public static void encodeIndexMinValue(CodecDataOutput cdo) {
    cdo.writeByte(Codec.BYTES_FLAG);
  }

  /**
   * encode a Row to CodecDataOutput
   *
   * @param cdo destination of data.
   * @param encodeType Key or Value.
   * @param value need to be encoded.
   */
  public void encode(CodecDataOutput cdo, EncodeType encodeType, Object value) {
    if (value == null) {
      encodeNull(cdo);
    } else {
      encodeNotNull(cdo, encodeType, value);
    }
  }

  /**
   * Encode a value to cdo.
   *  @param cdo destination of data.
   * @param encodeType Key or Value.
   * @param value need to be encoded.
   */
  protected abstract void encodeNotNull(CodecDataOutput cdo, EncodeType encodeType, Object value);

  /**
   * get origin default value
   * @param value a int value represents in string
   * @return a int object
   */
  public abstract Object getOriginDefaultValueNonNull(String value);

  public Object getOriginDefaultValue(String value) {
    if(value == null) return null;
    return getOriginDefaultValueNonNull(value);
  }

  public int getCollationCode() {
    return collation;
  }

  public long getLength() {
    return length;
  }

  public int getDecimal() {
    return decimal;
  }

  public void setFlag(int flag) {
    this.flag = flag;
  }

  public int getFlag() {
    return flag;
  }

  public List<String> getElems() {
    return this.elems;
  }

  public int getTypeCode() {
    return tp.getTypeCode();
  }

  public MySQLType getType() {
    return tp;
  }

  public static boolean hasNotNullFlag(int flag) {
    return (flag & NotNullFlag) > 0;
  }

  public static boolean hasNoDefaultFlag(int flag) {
    return (flag & NoDefaultValueFlag) > 0;
  }

  public static boolean hasAutoIncrementFlag(int flag) {
    return (flag & AutoIncrementFlag) > 0;
  }

  public static boolean hasUnsignedFlag(int flag) {
    return (flag & UnsignedFlag) > 0;
  }

  public static boolean hasZerofillFlag(int flag) {
    return (flag & ZerofillFlag) > 0;
  }

  public static boolean hasBinaryFlag(int flag) {
    return (flag & PriKeyFlag) > 0;
  }

  public static boolean hasUniKeyFlag(int flag) {
    return (flag & UniqueKeyFlag) > 0;
  }

  public static boolean hasMultipleKeyFlag(int flag) {
    return (flag & MultipleKeyFlag) > 0;
  }

  public static boolean hasTimestampFlag(int flag) {
    return (flag & TimestampFlag) > 0;
  }

  public static boolean hasOnUpdateNowFlag(int flag) {
    return (flag & OnUpdateNowFlag) > 0;
  }

  public boolean needCast(Object val) {
    // TODO: Add implementations
    return false;
  }

  @Override
  public String toString() {
    return this.getClass().getSimpleName();
  }

  @Override
  public boolean equals(Object other) {
    if (other instanceof DataType) {
      DataType otherType = (DataType) other;
      // tp implies Class is the same
      // and that might not always hold
      // TODO: reconsider design here
      return tp == otherType.tp
          && flag == otherType.flag
          && decimal == otherType.decimal
          && collation == otherType.collation
          && length == otherType.length
          && elems.equals(otherType.elems);
    }
    return false;
  }

  @Override
  public int hashCode() {
    return (int) (31
            * (tp.getTypeCode() == 0 ? 1 : tp.getTypeCode())
            * (flag == 0 ? 1 : flag)
            * (decimal == 0 ? 1 : decimal)
            * (collation == 0 ? 1 : collation)
            * (length == 0 ? 1 : length)
            * (elems.hashCode()));
  }
}

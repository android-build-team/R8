// Copyright (c) 2019, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.

package com.android.tools.r8.ir.analysis.proto.schema;

import com.android.tools.r8.ir.analysis.proto.ProtoUtils;
import com.android.tools.r8.utils.Pair;
import it.unimi.dsi.fastutil.ints.Int2IntArrayMap;
import it.unimi.dsi.fastutil.ints.Int2IntMap;
import it.unimi.dsi.fastutil.ints.IntOpenHashSet;
import it.unimi.dsi.fastutil.ints.IntSet;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.function.Predicate;

public class ProtoMessageInfo {

  public static final int BITS_PER_HAS_BITS_WORD = 32;

  public static class Builder {

    private int flags;

    private LinkedList<ProtoFieldInfo> fields;
    private LinkedList<ProtoFieldObject> hasBitsObjects;
    private LinkedList<Pair<ProtoObject, ProtoObject>> oneOfObjects;

    public void setFlags(int value) {
      this.flags = value;
    }

    public void addField(ProtoFieldInfo field) {
      if (fields == null) {
        fields = new LinkedList<>();
      }
      fields.add(field);
    }

    public void addHasBitsObject(ProtoFieldObject hasBitsObject) {
      if (hasBitsObjects == null) {
        hasBitsObjects = new LinkedList<>();
      }
      hasBitsObjects.add(hasBitsObject);
    }

    public void addOneOfObject(ProtoObject first, ProtoObject second) {
      if (oneOfObjects == null) {
        oneOfObjects = new LinkedList<>();
      }
      oneOfObjects.add(new Pair<>(first, second));
    }

    public ProtoMessageInfo build() {
      removeDeadFields();
      removeUnusedSharedData();
      return new ProtoMessageInfo(flags, fields, hasBitsObjects, oneOfObjects);
    }

    private void removeDeadFields() {
      if (fields != null) {
        Predicate<ProtoFieldInfo> isFieldDead =
            field -> {
              ProtoObject object =
                  field.getType().isOneOf()
                      ? oneOfObjects.get(field.getAuxData()).getFirst()
                      : field.getObjects().get(0);
              return object.isDeadProtoFieldObject();
            };
        fields.removeIf(isFieldDead);
      }
    }

    private void removeUnusedSharedData() {
      if (fields == null || fields.isEmpty()) {
        oneOfObjects = null;
        hasBitsObjects = null;
        return;
      }

      // Gather used "oneof" and "hasbits" indices.
      IntSet usedOneOfIndices = new IntOpenHashSet();
      IntSet usedHasBitsIndices = new IntOpenHashSet();
      for (ProtoFieldInfo field : fields) {
        if (field.hasAuxData()) {
          if (field.getType().isOneOf()) {
            usedOneOfIndices.add(field.getAuxData());
          } else {
            assert ProtoUtils.isProto2(flags) && field.getType().isSingular();
            usedHasBitsIndices.add(field.getAuxData() / BITS_PER_HAS_BITS_WORD);
          }
        }
      }

      if (hasBitsObjects != null) {
        for (int i = 0; i < hasBitsObjects.size(); i++) {
          ProtoFieldObject hasBitsObject = hasBitsObjects.get(i);
          if (hasBitsObject.isLiveProtoFieldObject()) {
            usedHasBitsIndices.add(i);
          }
        }
      }

      // Remove unused parts of "oneof" vector.
      Int2IntMap newOneOfObjectIndices = new Int2IntArrayMap();
      if (oneOfObjects != null) {
        Iterator<Pair<ProtoObject, ProtoObject>> oneOfObjectIterator = oneOfObjects.iterator();
        for (int i = 0, numberOfRemovedOneOfObjects = 0; i < oneOfObjects.size(); i++) {
          oneOfObjectIterator.next();
          if (usedOneOfIndices.contains(i)) {
            newOneOfObjectIndices.put(i, i - numberOfRemovedOneOfObjects);
          } else {
            oneOfObjectIterator.remove();
            numberOfRemovedOneOfObjects++;
          }
        }
      }

      // Remove unused parts of "hasbits" vector.
      Int2IntMap newHasBitsObjectIndices = new Int2IntArrayMap();
      if (hasBitsObjects != null) {
        Iterator<ProtoFieldObject> hasBitsObjectIterator = hasBitsObjects.iterator();
        int i = 0;
        int numberOfRemovedHasBitsObjects = 0;
        while (hasBitsObjectIterator.hasNext()) {
          hasBitsObjectIterator.next();
          if (usedHasBitsIndices.contains(i)) {
            newHasBitsObjectIndices.put(i, i - numberOfRemovedHasBitsObjects);
          } else {
            hasBitsObjectIterator.remove();
            numberOfRemovedHasBitsObjects++;
          }
          i++;
        }

        assert hasBitsObjects.stream().noneMatch(ProtoFieldObject::isDeadProtoFieldObject);
      }

      // Fix up references.
      for (ProtoFieldInfo field : fields) {
        if (field.hasAuxData()) {
          if (field.getType().isOneOf()) {
            field.setAuxData(newOneOfObjectIndices.get(field.getAuxData()));
          } else {
            int auxData = field.getAuxData();
            int oldHasBitsObjectIndex = auxData / BITS_PER_HAS_BITS_WORD;
            int oldHasBitsObjectBitIndex = auxData % BITS_PER_HAS_BITS_WORD;
            assert newHasBitsObjectIndices.containsValue(oldHasBitsObjectIndex);
            field.setAuxData(
                newHasBitsObjectIndices.get(oldHasBitsObjectIndex) * BITS_PER_HAS_BITS_WORD
                    + oldHasBitsObjectBitIndex);
          }
        }
      }
    }
  }

  private final int flags;

  private final LinkedList<ProtoFieldInfo> fields;
  private final LinkedList<ProtoFieldObject> hasBitsObjects;
  private final LinkedList<Pair<ProtoObject, ProtoObject>> oneOfObjects;

  private ProtoMessageInfo(
      int flags,
      LinkedList<ProtoFieldInfo> fields,
      LinkedList<ProtoFieldObject> hasBitsObjects,
      LinkedList<Pair<ProtoObject, ProtoObject>> oneOfObjects) {
    this.flags = flags;
    this.fields = fields;
    this.hasBitsObjects = hasBitsObjects;
    this.oneOfObjects = oneOfObjects;
  }

  public static ProtoMessageInfo.Builder builder() {
    return new ProtoMessageInfo.Builder();
  }

  public boolean isProto2() {
    return ProtoUtils.isProto2(flags);
  }

  public List<ProtoFieldInfo> getFields() {
    return fields;
  }

  public int getFlags() {
    return flags;
  }

  public List<ProtoFieldObject> getHasBitsObjects() {
    return hasBitsObjects;
  }

  public List<Pair<ProtoObject, ProtoObject>> getOneOfObjects() {
    return oneOfObjects;
  }

  public boolean hasFields() {
    return fields != null && !fields.isEmpty();
  }

  public int numberOfFields() {
    return fields != null ? fields.size() : 0;
  }

  public int numberOfHasBitsObjects() {
    return hasBitsObjects != null ? hasBitsObjects.size() : 0;
  }

  public int numberOfOneOfObjects() {
    return oneOfObjects != null ? oneOfObjects.size() : 0;
  }

  @Override
  public String toString() {
    StringBuilder builder = new StringBuilder("ProtoMessageInfo(fields=[");
    if (hasFields()) {
      Iterator<ProtoFieldInfo> fieldIterator = fields.iterator();
      builder.append(fieldIterator.next());
      while (fieldIterator.hasNext()) {
        builder.append(", ").append(fieldIterator.next());
      }
    }
    return builder.append("])").toString();
  }
}

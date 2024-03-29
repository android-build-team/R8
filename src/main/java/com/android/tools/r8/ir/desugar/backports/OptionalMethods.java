// Copyright (c) 2019, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.

package com.android.tools.r8.ir.desugar.backports;

import com.android.tools.r8.graph.DexMethod;
import com.android.tools.r8.ir.synthetic.TemplateMethodCode;
import com.android.tools.r8.utils.InternalOptions;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.OptionalInt;
import java.util.OptionalLong;
import java.util.function.Consumer;
import java.util.function.DoubleConsumer;
import java.util.function.IntConsumer;
import java.util.function.LongConsumer;
import java.util.function.Supplier;
import java.util.stream.Stream;

public class OptionalMethods extends TemplateMethodCode {

  public OptionalMethods(InternalOptions options, DexMethod method, String methodName) {
    super(options, method, methodName, method.proto.toDescriptorString());
  }

  public static <T> Optional<T> or(
      Optional<T> receiver, Supplier<? extends Optional<? extends T>> supplier) {
    Objects.requireNonNull(supplier);
    if (receiver.isPresent()) {
      return receiver;
    } else {
      @SuppressWarnings("unchecked")
      Optional<T> r = (Optional<T>) supplier.get();
      return Objects.requireNonNull(r);
    }
  }

  public static <T> void ifPresentOrElse(
      Optional<T> receiver, Consumer<? super T> action, Runnable emptyAction) {
    if (receiver.isPresent()) {
      action.accept(receiver.get());
    } else {
      emptyAction.run();
    }
  }

  public static void ifPresentOrElse(
      OptionalInt receiver, IntConsumer action, Runnable emptyAction) {
    if (receiver.isPresent()) {
      action.accept(receiver.getAsInt());
    } else {
      emptyAction.run();
    }
  }

  public static void ifPresentOrElse(
      OptionalLong receiver, LongConsumer action, Runnable emptyAction) {
    if (receiver.isPresent()) {
      action.accept(receiver.getAsLong());
    } else {
      emptyAction.run();
    }
  }

  public static void ifPresentOrElse(
      OptionalDouble receiver, DoubleConsumer action, Runnable emptyAction) {
    if (receiver.isPresent()) {
      action.accept(receiver.getAsDouble());
    } else {
      emptyAction.run();
    }
  }

  public static <T> Stream<T> stream(Optional<T> receiver) {
    if (receiver.isPresent()) {
      return Stream.of(receiver.get());
    } else {
      return Stream.empty();
    }
  }
}

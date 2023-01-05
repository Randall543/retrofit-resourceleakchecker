/*
 * Copyright (C) 2015 Square, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package retrofit2.converter.wire;

import com.squareup.wire.Message;
import com.squareup.wire.ProtoAdapter;
import java.io.IOException;
import okhttp3.MediaType;
import okhttp3.RequestBody;
import okio.Buffer;
import retrofit2.Converter;
import org.checkerframework.checker.mustcall.qual.*;
import org.checkerframework.checker.calledmethods.qual.*;
import org.checkerframework.dataflow.qual.SideEffectFree;
import org.checkerframework.common.returnsreceiver.qual.This;

final class WireRequestBodyConverter<T extends Message<T, ?>> implements Converter<T, RequestBody> {
  private static final MediaType MEDIA_TYPE = MediaType.get("application/x-protobuf");

  private final ProtoAdapter<T> adapter;

  WireRequestBodyConverter(ProtoAdapter<T> adapter) {
    this.adapter = adapter;
  }

  @Override
  @SuppressWarnings("calledmethods:required.method.not.called") // resource leak: If .encode() throws an exception then the only alias to buffer will be lost causing a resource leak.
  public RequestBody convert(T value) throws IOException {
    Buffer buffer = new Buffer();
    adapter.encode(buffer, value);
    return RequestBody.create(MEDIA_TYPE, buffer.snapshot());
  }
}

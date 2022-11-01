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
package retrofit2;

import java.util.Objects;
import javax.annotation.Nullable;
import okhttp3.Headers;
import okhttp3.Protocol;
import okhttp3.Request;
import okhttp3.ResponseBody;
import org.checkerframework.checker.mustcall.qual.*;
import org.checkerframework.checker.calledmethods.qual.*;
import org.checkerframework.dataflow.qual.*;
import org.checkerframework.common.returnsreceiver.qual.This;

//This class holds resources but doesn't implement closable or close? These resources must still be closed.
/** An HTTP response. */
@MustCall("close")
public final class Response<T> {
  /** Create a synthetic successful response with {@code body} as the deserialized body. */
  public static <T> Response<T> success(@Nullable T body) {
    return success(
        body,
        new okhttp3.Response.Builder() //
            .code(200)
            .message("OK")
            .protocol(Protocol.HTTP_1_1)
            .request(new Request.Builder().url("http://localhost/").build())
            .build());
  }

  /**
   * Create a synthetic successful response with an HTTP status code of {@code code} and {@code
   * body} as the deserialized body.
   */
  public static <T> Response<T> success(int code, @Nullable T body) {
    if (code < 200 || code >= 300) {
      throw new IllegalArgumentException("code < 200 or >= 300: " + code);
    }
    return success(
        body,
        new okhttp3.Response.Builder() //
            .code(code)
            .message("Response.success()")
            .protocol(Protocol.HTTP_1_1)
            .request(new Request.Builder().url("http://localhost/").build())
            .build());
  }

  /**
   * Create a synthetic successful response using {@code headers} with {@code body} as the
   * deserialized body.
   */
  public static <T> Response<T> success(@Nullable T body, Headers headers) {
    Objects.requireNonNull(headers, "headers == null");
    return success(
        body,
        new okhttp3.Response.Builder() //
            .code(200)
            .message("OK")
            .protocol(Protocol.HTTP_1_1)
            .headers(headers)
            .request(new Request.Builder().url("http://localhost/").build())
            .build());
  }

  /**
   * Create a successful response from {@code rawResponse} with {@code body} as the deserialized
   * body.
   */
  public static <T> Response<T> success(@Nullable T body, okhttp3.Response rawResponse) {
    Objects.requireNonNull(rawResponse, "rawResponse == null");
    if (!rawResponse.isSuccessful()) {
      throw new IllegalArgumentException("rawResponse must be successful response");
    }
    return new Response<>(rawResponse, body, null);
  }

  /**
   * Create a synthetic error response with an HTTP status code of {@code code} and {@code body} as
   * the error body.
   */
  public static <T> Response<T> error(int code, ResponseBody body) {
    Objects.requireNonNull(body, "body == null");
    if (code < 400) throw new IllegalArgumentException("code < 400: " + code);
    return error(
        body,
        new okhttp3.Response.Builder() //
            .body(new OkHttpCall.NoContentResponseBody(body.contentType(), body.contentLength()))
            .code(code)
            .message("Response.error()")
            .protocol(Protocol.HTTP_1_1)
            .request(new Request.Builder().url("http://localhost/").build())
            .build());
  }

  /** Create an error response from {@code rawResponse} with {@code body} as the error body. */
  public static @MustCallAlias <T> Response<T> error( @MustCallAlias ResponseBody body, okhttp3.Response rawResponse) { // A rawResponse doesn't need to be closed. Holds no resource. 
    Objects.requireNonNull(body, "body == null");
    Objects.requireNonNull(rawResponse, "rawResponse == null");
    if (rawResponse.isSuccessful()) {
      throw new IllegalArgumentException("rawResponse should not be successful response"); // This method is put into try-catch methods so it should be alright.
    }
    return new Response<>(rawResponse, null, body); //body must still be closed some way, some how.
  }

  private final  okhttp3.Response rawResponse;
  private final  @Nullable T body;
  private final @Owning @Nullable ResponseBody errorBody;   //A response body should be have method closed() but this class doesn't extend closable.

  private  @MustCallAlias Response(
     okhttp3.Response rawResponse,  @Nullable T body,  @Nullable  @MustCallAlias ResponseBody errorBody) { //perhaps This will allow the checker to connect the resources with the constructor.
    this.rawResponse = rawResponse;
    this.body = body;
    this.errorBody = errorBody;
  }

  /** The raw response from the HTTP client. */
  public okhttp3.Response raw() {
    return rawResponse;
  }

  /** HTTP status code. */
  public int code() {
    return rawResponse.code();
  }

  /** HTTP status message or null if unknown. */
  public String message() {
    return rawResponse.message();
  }

  /** HTTP headers. */
  public Headers headers() {
    return rawResponse.headers();
  }

  /** Returns true if {@link #code()} is in the range [200..300). */
  public boolean isSuccessful() {
    return rawResponse.isSuccessful();
  }

  /** The deserialized response body of a {@linkplain #isSuccessful() successful} response. */
  public @Nullable T body() {
    return body;
  }

  /** The raw response body of an {@linkplain #isSuccessful() unsuccessful} response. */
  public @Nullable @MustCallAlias ResponseBody errorBody(Response<T> this) {
    return errorBody;
  }

  @Override
  public String toString() {
    return rawResponse.toString();
  }
}

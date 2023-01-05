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

import static retrofit2.Utils.throwIfFatal;

import java.io.IOException;
import java.util.Objects;
import javax.annotation.Nullable;
import javax.annotation.concurrent.GuardedBy;
import okhttp3.MediaType;
import okhttp3.Request;
import okhttp3.ResponseBody;
import okio.Buffer;
import okio.BufferedSource;
import okio.ForwardingSource;
import okio.Okio;
import okio.Timeout;
import org.checkerframework.checker.mustcall.qual.*;
import org.checkerframework.checker.calledmethods.qual.*;
import org.checkerframework.framework.qual.*;
import org.checkerframework.dataflow.qual.SideEffectFree;
import org.checkerframework.common.returnsreceiver.qual.This;

final class OkHttpCall<T> implements Call<T> {
  private final RequestFactory requestFactory;
  private final Object[] args;
  private final okhttp3.Call.Factory callFactory;
  private final Converter<ResponseBody, T> responseConverter;

  private volatile boolean canceled;

  @GuardedBy("this")
  private @Nullable okhttp3.Call rawCall;

  @GuardedBy("this") // Either a RuntimeException, non-fatal Error, or IOException.
  private @Nullable Throwable creationFailure;

  @GuardedBy("this")
  private boolean executed;

  OkHttpCall(
      RequestFactory requestFactory,
      Object[] args,
      okhttp3.Call.Factory callFactory,
      Converter<ResponseBody, T> responseConverter) {
    this.requestFactory = requestFactory;
    this.args = args;
    this.callFactory = callFactory;
    this.responseConverter = responseConverter;
  }

  @SuppressWarnings("CloneDoesntCallSuperClone") // We are a final type & this saves clearing state.
  @Override
  public OkHttpCall<T> clone() {
    return new OkHttpCall<>(requestFactory, args, callFactory, responseConverter);
  }

  @Override
  public synchronized Request request() {
    try {
      return getRawCall().request();
    } catch (IOException e) {
      throw new RuntimeException("Unable to create request.", e);
    }
  }

  @Override
  public synchronized Timeout timeout() {
    try {
      return getRawCall().timeout();
    } catch (IOException e) {
      throw new RuntimeException("Unable to create call.", e);
    }
  }

  /**
   * Returns the raw call, initializing it if necessary. Throws if initializing the raw call throws,
   * or has thrown in previous attempts to create it.
   */
  @GuardedBy("this")
  private okhttp3.Call getRawCall() throws IOException {
    okhttp3.Call call = rawCall;
    if (call != null) return call;

    // Re-throw previous failures if this isn't the first attempt.
    if (creationFailure != null) {
      if (creationFailure instanceof IOException) {
        throw (IOException) creationFailure;
      } else if (creationFailure instanceof RuntimeException) {
        throw (RuntimeException) creationFailure;
      } else {
        throw (Error) creationFailure;
      }
    }

    // Create and remember either the success or the failure.
    try {
      return rawCall = createRawCall();
    } catch (RuntimeException | Error | IOException e) {
      throwIfFatal(e); // Do not assign a fatal error to creationFailure.
      creationFailure = e;
      throw e;
    }
  }

  @Override
  public void enqueue(final Callback<T> callback) {
    Objects.requireNonNull(callback, "callback == null");

    okhttp3.Call call;
    Throwable failure;

    synchronized (this) {
      if (executed) throw new IllegalStateException("Already executed.");
      executed = true;

      call = rawCall;
      failure = creationFailure;
      if (call == null && failure == null) {
        try {
          call = rawCall = createRawCall();
        } catch (Throwable t) {
          throwIfFatal(t);
          failure = creationFailure = t;
        }
      }
    }

    if (failure != null) {
      callback.onFailure(this, failure);
      return;
    }

    if (canceled) {
      call.cancel();
    }
    call.enqueue(
      new okhttp3.Callback() {
        @Override
        @SuppressWarnings("calledmethods:required.method.not.called") //Response<T> response will go out scope through callback.onResponse() does not take responsiblity for closing response and the resource will still be active. This is also anonymous class therefore there is no way to alias the constructor and response .//https://javadoc.io/doc/com.squareup.okhttp3/okhttp/3.14.9/okhttp3/Callback.html
          public void onResponse(okhttp3.Call call, okhttp3.Response rawResponse) {
            Response<T> response;
            try {
              response = parseResponse(rawResponse);
            } catch (Throwable e) {
              throwIfFatal(e);
              callFailure(e);
              return;
            }

            try {
              callback.onResponse(OkHttpCall.this, response);
            } catch (Throwable t) {
              throwIfFatal(t);
              t.printStackTrace(); // TODO this is not great
            }
          }

          @Override
          public void onFailure(okhttp3.Call call, IOException e) {
            callFailure(e);
          }

          private void callFailure(Throwable e) {
            try {
              callback.onFailure(OkHttpCall.this, e);
            } catch (Throwable t) {
              throwIfFatal(t);
              t.printStackTrace(); // TODO this is not great
            }
          }
        });
  }

  @Override
  public synchronized boolean isExecuted() {
    return executed;
  }

  @Override
  @SuppressWarnings("mustcall:override.receiver")  //Purpose of annoation is to suppress the warning the checker throws when a implemention of Call<T> overrides this method. Full explanation is in the Call.Java file.
  public Response<T> execute() throws IOException {
    okhttp3.Call call;

    synchronized (this) {
      if (executed) throw new IllegalStateException("Already executed.");
      executed = true;

      call = getRawCall();
    }

    if (canceled) {
      call.cancel();
    }

    return parseResponse(call.execute());
  }

  private okhttp3.Call createRawCall() throws IOException {
    okhttp3.Call call = callFactory.newCall(requestFactory.create(args));
    if (call == null) {
      throw new NullPointerException("Call.Factory returned null.");
    }
    return call;
  }
  /*
   * Response.error() or Response.success()
   * Both of these methods throw warnings because they take input annotated with @MustCall(close) then return @MustCall(errorBody). These warnings are false positives.
   * This is done to communicate to the checker that the responsibily of closing the resources held by Response<T> is not owned by the mentioned class.
   * Another reason is to communicate the different mustcall obligations of a success or error Response<T>.
   */
  @SuppressWarnings("mustcall:return")
  Response<T> parseResponse( okhttp3.Response rawResponse) throws IOException {
    ResponseBody rawBody = rawResponse.body();

    // Remove the body's source (the only stateful object) so we can pass the response along.
    rawResponse =
        rawResponse
            .newBuilder()
            .body(new NoContentResponseBody(rawBody.contentType(), rawBody.contentLength()))
            .build();

    int code = rawResponse.code();
    if (code < 200 || code >= 300) {
      try {
        // Buffer the entire body to avoid future I/O.
        ResponseBody bufferedBody = Utils.buffer(rawBody);
        return Response.error(bufferedBody, rawResponse); 
      } finally {
        rawBody.close();
      }
    }

    if (code == 204 || code == 205) {
      rawBody.close();
      return Response.success(null, rawResponse);
    }

    ExceptionCatchingResponseBody catchingBody = new ExceptionCatchingResponseBody(rawBody);
    try {
      T body = responseConverter.convert(catchingBody); // this method essentially converts Responsebody to ExceptionCatchingResponseBody
      return Response.success(body, rawResponse);
    } catch (RuntimeException e) {
      // If the underlying source threw an exception, propagate that rather than indicating it was
      // a runtime exception.
      catchingBody.throwIfCaught();
      throw e;
    }
  }

  @Override
  public void cancel() {
    canceled = true;

    okhttp3.Call call;
    synchronized (this) {
      call = rawCall;
    }
    if (call != null) {
      call.cancel();
    }
  }

  @Override
  public boolean isCanceled() {
    if (canceled) {
      return true;
    }
    synchronized (this) {
      return rawCall != null && rawCall.isCanceled();
    }
  }
  @MustCall({})
  @SuppressWarnings("mustcall:inconsistent.mustcall.subtype")  //Types of @MustCall({}) and @InheritableMustCall("close") are inconsistent, but child class has no need to be closed because there is no resource to close therefore this is a false positive.
  static final class NoContentResponseBody extends ResponseBody {
    private final @Nullable MediaType contentType;
    private final long contentLength;

    @SuppressWarnings("mustcall:super.invocation") //The constructor @MustCall must match it's class.
    NoContentResponseBody(@Nullable MediaType contentType, long contentLength) {
      this.contentType = contentType;
      this.contentLength = contentLength;
    }

    @Override
    public MediaType contentType() {
      return contentType;
    }

    @Override
    public long contentLength() {
      return contentLength;
    }

    @Override
    public BufferedSource source() {
      throw new IllegalStateException("Cannot read raw response body of a converted body.");
    }
  }
  static final class ExceptionCatchingResponseBody extends ResponseBody {
    private final @Owning ResponseBody delegate;
    private final BufferedSource delegateSource;
    @Nullable IOException thrownException;
    @SuppressWarnings("mustcall:annotations.on.use") //Resource leak checker can not handle this situation. The Type and annotation are inconsistent due to anonymous class. [error: [annotations.on.use] invalid type: annotations [@PolyMustCall] conflict with declaration of type retrofit2.OkHttpCall.ExceptionCatchingResponseBody]
    @MustCallAlias ExceptionCatchingResponseBody(@MustCallAlias ResponseBody delegate) {
      this.delegate = delegate;
      this.delegateSource =
          Okio.buffer(
              new ForwardingSource(delegate.source())
              {
                @Override
                public long read(Buffer sink, long byteCount) throws IOException {
                  try {
                    return super.read(sink, byteCount);
                  } catch (IOException e) {
                    thrownException = e;
                    throw e;
                  }
                }
              });
    }

    @Override
    public MediaType contentType() {
      return delegate.contentType();
    }

    @Override
    public long contentLength() {
      return delegate.contentLength();
    }

    @Override
    public BufferedSource source() {
      return delegateSource;
    }

    @Override
    @EnsuresCalledMethods(value = "delegate", methods = "close")
    public void close() {
      delegate.close();
    }

    void throwIfCaught() throws IOException {
      if (thrownException != null) {
        throw thrownException;
      }
    }
  }
}

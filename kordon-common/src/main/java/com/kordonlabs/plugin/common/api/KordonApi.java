package com.kordonlabs.plugin.common.api;

import com.kordonlabs.plugin.common.api.dto.HeartbeatRequest;
import com.kordonlabs.plugin.common.api.dto.HeartbeatResponse;
import com.kordonlabs.plugin.common.api.dto.ListsRequest;
import com.kordonlabs.plugin.common.api.dto.ListsResponse;
import com.kordonlabs.plugin.common.api.dto.LoginRequest;
import com.kordonlabs.plugin.common.api.dto.LoginResponse;
import com.kordonlabs.plugin.common.api.dto.LookupRequest;
import com.kordonlabs.plugin.common.api.dto.LookupResponse;
import com.kordonlabs.plugin.common.api.dto.SyncRequest;
import com.kordonlabs.plugin.common.api.dto.SyncResponse;
import com.kordonlabs.plugin.common.install.Credentials;
import java.util.concurrent.CompletableFuture;

/**
 * The panel's plugin API {@code /api/v1/mc/*}. Every future completes normally with an {@link ApiResult}, never
 * exceptionally: network problems are outcomes, not exceptions. Timeouts and retries are the implementation's job.
 * A "stale connection" failure is a {@code ConnectException}, an {@code EOFException}, "header parser received no
 * bytes" or a connection reset, never an {@code HttpTimeoutException}. Redirects are never followed.
 */
public interface KordonApi {

    /** {@code POST /login}: 3 s, never retried. */
    CompletableFuture<ApiResult<LoginResponse>> login(LoginRequest request);

    /** {@code POST /heartbeat}: 10 s, one retry on a stale-connection failure. */
    CompletableFuture<ApiResult<HeartbeatResponse>> heartbeat(HeartbeatRequest request);

    /**
     * A heartbeat with candidate credentials instead of the ones in use, for {@code /kordon link}. A null
     * {@code with} means the credentials in use. The default implementation ignores {@code with}.
     */
    default CompletableFuture<ApiResult<HeartbeatResponse>> heartbeat(HeartbeatRequest request, Credentials with) {
        return heartbeat(request);
    }

    /** {@code POST /sync}: 15 s, one retry on a stale-connection failure. */
    CompletableFuture<ApiResult<SyncResponse>> sync(SyncRequest request);

    /** {@code POST /lists}: 8 s, one retry on a stale-connection failure (the result then has {@code retried = true}). */
    CompletableFuture<ApiResult<ListsResponse>> lists(ListsRequest request);

    /** {@code POST /lookup}: 8 s, one retry on a stale-connection failure. */
    CompletableFuture<ApiResult<LookupResponse>> lookup(LookupRequest request);
}

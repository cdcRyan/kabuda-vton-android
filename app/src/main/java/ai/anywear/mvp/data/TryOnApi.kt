package ai.anywear.mvp.data

import okhttp3.Interceptor
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.RequestBody
import okhttp3.ResponseBody
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.Query
import retrofit2.http.Multipart
import retrofit2.http.POST
import retrofit2.http.Part
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

interface TryOnApi {
    @POST("api/tokens")
    suspend fun getToken(@Body request: TokenRequest): Response<TokenResponse>

    @POST("api/queue/join")
    suspend fun joinQueue(@Body request: QueueJoinRequest): Response<QueueJoinResponse>

    @GET("api/queue/status")
    suspend fun queueStatus(@Query("ticket") ticket: String): Response<QueueStatusResponse>

    @POST("api/queue/heartbeat")
    suspend fun queueHeartbeat(@Body request: QueueJoinRequest): Response<ResponseBody>

    @POST("api/queue/leave")
    suspend fun leaveQueue(@Body request: QueueJoinRequest): Response<ResponseBody>

    @POST("api/consumer-billing/status")
    suspend fun billingStatus(@Body request: QueueJoinRequest): Response<BillingStatusResponse>

    @POST("api/consumer-billing/heartbeat")
    suspend fun billingHeartbeat(@Body request: BillingHeartbeatRequest): Response<BillingHeartbeatResponse>

    @POST("api/log")
    suspend fun log(@Body event: LogEvent): Response<ResponseBody>

    @Multipart
    @POST("api/uploads/feedback-frame")
    suspend fun uploadFeedbackFrame(
        @Part label: MultipartBody.Part,
        @Part frame: MultipartBody.Part,
        @Part sessionId: MultipartBody.Part,
        @Part tryonId: MultipartBody.Part
    ): Response<ResponseBody>
}

object TryOnApiClient {
    private val clients = ConcurrentHashMap<String, TryOnApi>()

    fun apiFor(baseUrl: String, apiKeyProvider: () -> String = { "" }): TryOnApi {
        val normalized = BackendConfig.normalize(baseUrl)
        require(normalized.isNotBlank()) { "API base URL is empty" }
        return clients.getOrPut(normalized) {
            val logging = HttpLoggingInterceptor().apply {
                level = HttpLoggingInterceptor.Level.BASIC
            }
            val client = OkHttpClient.Builder()
                .connectTimeout(20, TimeUnit.SECONDS)
                .readTimeout(60, TimeUnit.SECONDS)
                .writeTimeout(60, TimeUnit.SECONDS)
                .addInterceptor(Interceptor { chain ->
                    val key = apiKeyProvider().trim()
                    val request = chain.request().newBuilder()
                    if (key.isNotBlank()) {
                        request.header("X-Api-Key", key)
                    }
                    chain.proceed(request.build())
                })
                .addInterceptor(logging)
                .build()

            Retrofit.Builder()
                .baseUrl(normalized)
                .client(client)
                .addConverterFactory(GsonConverterFactory.create())
                .build()
                .create(TryOnApi::class.java)
        }
    }
}

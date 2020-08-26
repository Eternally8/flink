/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.flink.streaming.connectors.elasticsearch7;

import org.apache.flink.annotation.Internal;
import org.apache.flink.annotation.VisibleForTesting;
import org.apache.flink.api.common.serialization.SerializationSchema;
import org.apache.flink.api.java.tuple.Tuple2;
import org.apache.flink.configuration.MemorySize;
import org.apache.flink.streaming.api.functions.sink.SinkFunction;
import org.apache.flink.streaming.connectors.elasticsearch.ActionRequestFailureHandler;
import org.apache.flink.streaming.connectors.elasticsearch.ElasticsearchSinkBase;
import org.apache.flink.streaming.connectors.elasticsearch.ElasticsearchUpsertTableSinkBase;
import org.apache.flink.table.api.TableSchema;
import org.apache.flink.types.Row;

import org.apache.http.HttpHost;
import org.apache.http.auth.AuthScope;
import org.apache.http.auth.UsernamePasswordCredentials;
import org.apache.http.client.CredentialsProvider;
import org.apache.http.conn.ssl.NoopHostnameVerifier;
import org.apache.http.impl.client.BasicCredentialsProvider;
import org.apache.http.ssl.SSLContextBuilder;
import org.apache.http.ssl.SSLContexts;
import org.elasticsearch.action.delete.DeleteRequest;
import org.elasticsearch.action.index.IndexRequest;
import org.elasticsearch.action.update.UpdateRequest;
import org.elasticsearch.client.RestClientBuilder;
import org.elasticsearch.common.xcontent.XContentType;

import javax.annotation.Nullable;
import javax.net.ssl.SSLContext;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.KeyManagementException;
import java.security.KeyStore;
import java.security.KeyStoreException;
import java.security.NoSuchAlgorithmException;
import java.security.cert.Certificate;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;

import static org.apache.flink.streaming.connectors.elasticsearch.ElasticsearchUpsertTableSinkBase.SinkOption.BULK_FLUSH_BACKOFF_DELAY;
import static org.apache.flink.streaming.connectors.elasticsearch.ElasticsearchUpsertTableSinkBase.SinkOption.BULK_FLUSH_BACKOFF_ENABLED;
import static org.apache.flink.streaming.connectors.elasticsearch.ElasticsearchUpsertTableSinkBase.SinkOption.BULK_FLUSH_BACKOFF_RETRIES;
import static org.apache.flink.streaming.connectors.elasticsearch.ElasticsearchUpsertTableSinkBase.SinkOption.BULK_FLUSH_BACKOFF_TYPE;
import static org.apache.flink.streaming.connectors.elasticsearch.ElasticsearchUpsertTableSinkBase.SinkOption.BULK_FLUSH_INTERVAL;
import static org.apache.flink.streaming.connectors.elasticsearch.ElasticsearchUpsertTableSinkBase.SinkOption.BULK_FLUSH_MAX_ACTIONS;
import static org.apache.flink.streaming.connectors.elasticsearch.ElasticsearchUpsertTableSinkBase.SinkOption.BULK_FLUSH_MAX_SIZE;
import static org.apache.flink.streaming.connectors.elasticsearch.ElasticsearchUpsertTableSinkBase.SinkOption.CERTIFICATE;
import static org.apache.flink.streaming.connectors.elasticsearch.ElasticsearchUpsertTableSinkBase.SinkOption.DISABLE_FLUSH_ON_CHECKPOINT;
import static org.apache.flink.streaming.connectors.elasticsearch.ElasticsearchUpsertTableSinkBase.SinkOption.PASSWORD;
import static org.apache.flink.streaming.connectors.elasticsearch.ElasticsearchUpsertTableSinkBase.SinkOption.REST_PATH_PREFIX;
import static org.apache.flink.streaming.connectors.elasticsearch.ElasticsearchUpsertTableSinkBase.SinkOption.USERNAME;

/**
 * Version-specific upsert table sink for Elasticsearch 7.
 */
@Internal
public class Elasticsearch7UpsertTableSink extends ElasticsearchUpsertTableSinkBase {

	@VisibleForTesting
	static final RequestFactory UPDATE_REQUEST_FACTORY =
		new Elasticsearch7RequestFactory();

	public Elasticsearch7UpsertTableSink(
			boolean isAppendOnly,
			TableSchema schema,
			List<Host> hosts,
			String index,
			String keyDelimiter,
			String keyNullLiteral,
			SerializationSchema<Row> serializationSchema,
			XContentType contentType,
			ActionRequestFailureHandler failureHandler,
			Map<SinkOption, String> sinkOptions) {

		super(
			isAppendOnly,
			schema,
			hosts,
			index,
			"",
			keyDelimiter,
			keyNullLiteral,
			serializationSchema,
			contentType,
			failureHandler,
			sinkOptions,
			UPDATE_REQUEST_FACTORY);
	}

	@VisibleForTesting
	Elasticsearch7UpsertTableSink(
		boolean isAppendOnly,
		TableSchema schema,
		List<Host> hosts,
		String index,
		String docType,
		String keyDelimiter,
		String keyNullLiteral,
		SerializationSchema<Row> serializationSchema,
		XContentType contentType,
		ActionRequestFailureHandler failureHandler,
		Map<SinkOption, String> sinkOptions) {

		super(
			isAppendOnly,
			schema,
			hosts,
			index,
			docType,
			keyDelimiter,
			keyNullLiteral,
			serializationSchema,
			contentType,
			failureHandler,
			sinkOptions,
			UPDATE_REQUEST_FACTORY);
	}

	@Override
	protected ElasticsearchUpsertTableSinkBase copy(
			boolean isAppendOnly,
			TableSchema schema,
			List<Host> hosts,
			String index,
			String docType,
			String keyDelimiter,
			String keyNullLiteral,
			SerializationSchema<Row> serializationSchema,
			XContentType contentType,
			ActionRequestFailureHandler failureHandler,
			Map<SinkOption, String> sinkOptions,
			RequestFactory requestFactory) {

		return new Elasticsearch7UpsertTableSink(
			isAppendOnly,
			schema,
			hosts,
			index,
			keyDelimiter,
			keyNullLiteral,
			serializationSchema,
			contentType,
			failureHandler,
			sinkOptions);
	}

	@Override
	protected SinkFunction<Tuple2<Boolean, Row>> createSinkFunction(
			List<Host> hosts,
			ActionRequestFailureHandler failureHandler,
			Map<SinkOption, String> sinkOptions,
			ElasticsearchUpsertSinkFunction upsertSinkFunction) {

		final List<HttpHost> httpHosts = hosts.stream()
			.map((host) -> new HttpHost(host.hostname, host.port, host.protocol))
			.collect(Collectors.toList());

		final ElasticsearchSink.Builder<Tuple2<Boolean, Row>> builder = createBuilder(upsertSinkFunction, httpHosts);

		builder.setFailureHandler(failureHandler);

		Optional.ofNullable(sinkOptions.get(BULK_FLUSH_MAX_ACTIONS))
			.ifPresent(v -> builder.setBulkFlushMaxActions(Integer.valueOf(v)));

		Optional.ofNullable(sinkOptions.get(BULK_FLUSH_MAX_SIZE))
			.ifPresent(v -> builder.setBulkFlushMaxSizeMb(MemorySize.parse(v).getMebiBytes()));

		Optional.ofNullable(sinkOptions.get(BULK_FLUSH_INTERVAL))
			.ifPresent(v -> builder.setBulkFlushInterval(Long.valueOf(v)));

		Optional.ofNullable(sinkOptions.get(BULK_FLUSH_BACKOFF_ENABLED))
			.ifPresent(v -> builder.setBulkFlushBackoff(Boolean.valueOf(v)));

		Optional.ofNullable(sinkOptions.get(BULK_FLUSH_BACKOFF_TYPE))
			.ifPresent(v -> builder.setBulkFlushBackoffType(ElasticsearchSinkBase.FlushBackoffType.valueOf(v)));

		Optional.ofNullable(sinkOptions.get(BULK_FLUSH_BACKOFF_RETRIES))
			.ifPresent(v -> builder.setBulkFlushBackoffRetries(Integer.valueOf(v)));

		Optional.ofNullable(sinkOptions.get(BULK_FLUSH_BACKOFF_DELAY))
			.ifPresent(v -> builder.setBulkFlushBackoffDelay(Long.valueOf(v)));

		String username = sinkOptions.get(USERNAME);
		String password = sinkOptions.get(PASSWORD);
		String pathPrefix = sinkOptions.get(REST_PATH_PREFIX);
		String certificate = sinkOptions.get(CERTIFICATE);

		if (Optional.ofNullable(username).isPresent() && Optional.ofNullable(password).isPresent()) {
			builder.setRestClientFactory(new AuthRestClientFactory(username, password, pathPrefix, certificate));
		} else {
			builder.setRestClientFactory(new DefaultRestClientFactory(pathPrefix));
		}

		final ElasticsearchSink<Tuple2<Boolean, Row>> sink = builder.build();

		Optional.ofNullable(sinkOptions.get(DISABLE_FLUSH_ON_CHECKPOINT))
			.ifPresent(v -> {
				if (Boolean.valueOf(v)) {
					sink.disableFlushOnCheckpoint();
				}
			});

		return sink;
	}

	@VisibleForTesting
	ElasticsearchSink.Builder<Tuple2<Boolean, Row>> createBuilder(
			ElasticsearchUpsertSinkFunction upsertSinkFunction,
			List<HttpHost> httpHosts) {
		return new ElasticsearchSink.Builder<>(httpHosts, upsertSinkFunction);
	}

	/**
	 * This class implements {@link RestClientFactory}, used for es with authentication.
	 */
	static class AuthRestClientFactory implements RestClientFactory {

		private String userName;

		private String password;

		private String pathPrefix;

		private String certificate;

		private transient CredentialsProvider credentialsProvider;

		private transient SSLContext sslContext;

		public AuthRestClientFactory(@Nullable String userName, @Nullable String password, @Nullable String pathPrefix, @Nullable String certificate) {
			this.userName = userName;
			this.password = password;
			this.pathPrefix = pathPrefix;
			this.certificate = certificate;
		}

		@Override
		public void configureRestClientBuilder(RestClientBuilder restClientBuilder) {
			if (credentialsProvider == null) {
				credentialsProvider = new BasicCredentialsProvider();
				credentialsProvider.setCredentials(AuthScope.ANY, new UsernamePasswordCredentials(userName, password));
			}

			if (pathPrefix != null) {
				restClientBuilder.setPathPrefix(pathPrefix);
			}

			if (certificate != null){
				Path caCertificatePath  = Paths.get(certificate); //新生成的keystore文件路径
				try {
					CertificateFactory factory = CertificateFactory.getInstance("X.509");
					Certificate trustedCa;
					try (InputStream is = Files.newInputStream(caCertificatePath)) {
						trustedCa = factory.generateCertificate(is);
					}
					KeyStore trustStore = KeyStore.getInstance("pkcs12");
					trustStore.load(null, null);
					trustStore.setCertificateEntry("ca", trustedCa);
					SSLContextBuilder sslContextBuilder = SSLContexts.custom().loadTrustMaterial(trustStore, null);
					sslContext = sslContextBuilder.build();
				} catch (CertificateException e) {
					e.printStackTrace();
				} catch (IOException e) {
					e.printStackTrace();
				} catch (KeyStoreException e) {
					e.printStackTrace();
				} catch (NoSuchAlgorithmException e) {
					e.printStackTrace();
				} catch (KeyManagementException e) {
					e.printStackTrace();
				}

				restClientBuilder.setHttpClientConfigCallback(httpAsyncClientBuilder ->
					httpAsyncClientBuilder.setSSLContext(sslContext).setDefaultCredentialsProvider(credentialsProvider).setSSLHostnameVerifier(NoopHostnameVerifier.INSTANCE));
			} else {
				restClientBuilder.setHttpClientConfigCallback(httpAsyncClientBuilder ->
					httpAsyncClientBuilder.setDefaultCredentialsProvider(credentialsProvider));
			}

		}

		@Override
		public boolean equals(Object o) {
			if (this == o) {
				return true;
			}
			if (o == null || getClass() != o.getClass()) {
				return false;
			}
			AuthRestClientFactory that = (AuthRestClientFactory) o;
			return Objects.equals(userName, that.userName) &&
				Objects.equals(password, that.password) &&
				Objects.equals(pathPrefix, that.pathPrefix);
		}

		@Override
		public int hashCode() {
			return Objects.hash(userName, password, pathPrefix);
		}
	}

	// --------------------------------------------------------------------------------------------
	// Helper classes
	// --------------------------------------------------------------------------------------------

	/**
	 * Serializable {@link RestClientFactory} used by the sink.
	 */
	@VisibleForTesting
	static class DefaultRestClientFactory implements RestClientFactory {

		private String pathPrefix;

		public DefaultRestClientFactory(@Nullable String pathPrefix) {
			this.pathPrefix = pathPrefix;
		}

		@Override
		public void configureRestClientBuilder(RestClientBuilder restClientBuilder) {
			if (pathPrefix != null) {
				restClientBuilder.setPathPrefix(pathPrefix);
			}
		}

		@Override
		public boolean equals(Object o) {
			if (this == o) {
				return true;
			}
			if (o == null || getClass() != o.getClass()) {
				return false;
			}
			DefaultRestClientFactory that = (DefaultRestClientFactory) o;
			return Objects.equals(pathPrefix, that.pathPrefix);
		}

		@Override
		public int hashCode() {
			return Objects.hash(pathPrefix);
		}
	}

	/**
	 * Version-specific creation of {@link org.elasticsearch.action.ActionRequest}s used by the sink.
	 */
	private static class Elasticsearch7RequestFactory implements RequestFactory {

		@Override
		public UpdateRequest createUpdateRequest(
				String index,
				String docType,
				String key,
				XContentType contentType,
				byte[] document) {
			return new UpdateRequest(index, key)
				.doc(document, contentType)
				.upsert(document, contentType);
		}

		@Override
		public IndexRequest createIndexRequest(
				String index,
				String docType,
				XContentType contentType,
				byte[] document) {
			return new IndexRequest(index)
				.source(document, contentType);
		}

		@Override
		public DeleteRequest createDeleteRequest(String index, String docType, String key) {
			return new DeleteRequest(index, key);
		}
	}
}

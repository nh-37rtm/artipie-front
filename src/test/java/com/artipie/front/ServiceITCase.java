/*
 * The MIT License (MIT) Copyright (c) 2022 artipie.com
 * https://github.com/artipie/front/LICENSE.txt
 */
package com.artipie.front;

import com.amihaiemil.eoyaml.Yaml;
import com.artipie.asto.test.TestResource;
import com.artipie.front.auth.ApiTokens;
import com.artipie.front.settings.ArtipieYaml;
import com.artipie.front.settings.ArtipieYamlTest;
import com.google.common.collect.Lists;
import java.io.IOException;
import java.io.StringReader;
import java.io.UncheckedIOException;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Optional;
import java.util.Random;
import javax.json.Json;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpDelete;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpHead;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.client.methods.HttpPut;
import org.apache.http.entity.ByteArrayEntity;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.util.EntityUtils;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.http.MimeTypes;
import org.hamcrest.MatcherAssert;
import org.hamcrest.Matchers;
import org.hamcrest.core.IsEqual;
import org.hamcrest.core.IsNot;
import org.hamcrest.core.StringContains;
import org.hamcrest.text.StringContainsInOrder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Service IT.
 * @since 0.1
 * @checkstyle ClassDataAbstractionCouplingCheck (500 lines)
 * @checkstyle MagicNumberCheck (500 lines)
 */
@SuppressWarnings("PMD.AvoidDuplicateLiterals")
class ServiceITCase {

    /**
     * Temp directory.
     * @checkstyle VisibilityModifierCheck (10 lines)
     */
    @TempDir
    Path tmp;

    /**
     * Test service instance.
     */
    private Service service;

    /**
     * Test port.
     */
    private int port;

    /**
     * Test http client.
     */
    private CloseableHttpClient http;

    @BeforeEach
    void init() throws IOException {
        final byte[] key = new byte[20];
        Arrays.fill(key, (byte) 0);
        final String creds = "_credentials.yaml";
        Files.write(
            this.tmp.resolve(creds),
            new TestResource("ServiceITCase/_credentials.yaml").asBytes()
        );
        Files.write(
            this.tmp.resolve("_api_permissions.yml"),
            new TestResource("ServiceITCase/_api_permissions.yml").asBytes()
        );
        this.tmp.resolve("repos").toFile().mkdir();
        Files.write(
            this.tmp.resolve("repos").resolve("maven-repo.yaml"),
            new TestResource("ServiceITCase/maven-repo.yaml").asBytes()
        );
        this.service = new Service(
            new ApiTokens(key, new Random()),
            new ArtipieYaml(
                ArtipieYamlTest.config(
                    this.tmp.toString(),
                    Optional.of(
                        Yaml.createYamlMappingBuilder().add("type", "file")
                            .add("path", creds).build()
                    )
                )
            )
        );
        this.port = this.randomFreePort();
        this.service.start(this.port);
        this.http = HttpClients.createDefault();
    }

    @Test
    void aliceCanGetRepoAndUsers() {
        final String token = this.token("Alice", "wonderland");
        MatcherAssert.assertThat(
            "Failed to obtain auth token",
            token,
            new IsNot<>(Matchers.emptyString())
        );
        MatcherAssert.assertThat(
            "Failed to get users info",
            this.get("/api/users", token),
            new StringContainsInOrder(Lists.newArrayList("Alice", "Aladdin"))
        );
        MatcherAssert.assertThat(
            "Failed to get check Aladdin exists",
            this.head("/api/users/Aladdin", token),
            new IsEqual<>(HttpStatus.OK_200)
        );
        MatcherAssert.assertThat(
            "Failed to get Alice info",
            this.get("/api/users/Alice", token),
            new StringContains("Alice")
        );
        MatcherAssert.assertThat(
            "Failed to get repos info",
            this.get("/api/repositories", token),
            new StringContains("maven-repo")
        );
        MatcherAssert.assertThat(
            "Failed to check maven repo exists",
            this.head("/api/repositories/maven-repo", token),
            new IsEqual<>(HttpStatus.OK_200)
        );
        MatcherAssert.assertThat(
            "Failed to get maven repo info",
            this.get("/api/repositories/maven-repo", token),
            new StringContainsInOrder(Lists.newArrayList("type", "maven"))
        );
    }

    @Test
    void aladdinCanWriteUsers() {
        final String aladdin = this.token("Aladdin", "opensesame");
        MatcherAssert.assertThat(
            "Failed to obtain auth token",
            aladdin,
            new IsNot<>(Matchers.emptyString())
        );
        MatcherAssert.assertThat(
            "Failed to create new user",
            this.put(
                "/api/users/Olga", aladdin,
                Json.createObjectBuilder().add(
                    "Olga",
                    Json.createObjectBuilder().add("type", "plain").add("pass", "123").build()
                ).build().toString()
            ),
            new IsEqual<>(HttpStatus.CREATED_201)
        );
        MatcherAssert.assertThat(
            "Failed to check Olga exists",
            this.get("/api/users/Olga", aladdin),
            new StringContains("Olga")
        );
        MatcherAssert.assertThat(
            "Failed to delete Alice",
            this.delete("/api/users/Alice", aladdin),
            new IsEqual<>(HttpStatus.OK_200)
        );
        MatcherAssert.assertThat(
            "Failed to check Alice does not exist",
            this.head("/api/users/Alice", aladdin),
            new IsEqual<>(HttpStatus.NOT_FOUND_404)
        );
    }

    @AfterEach
    void stop() throws IOException {
        this.service.stop();
        this.http.close();
    }

    private int randomFreePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    private String token(final String name, final String pswd) {
        final HttpPost request = new HttpPost(
            String.format("http://localhost:%d/token", this.port)
        );
        request.addHeader(
            HttpHeader.CONTENT_TYPE.toString(),
            MimeTypes.Type.APPLICATION_JSON.asString()
        );
        request.setEntity(
            new ByteArrayEntity(
                Json.createObjectBuilder().add("name", name).add("pass", pswd).build().toString()
                    .getBytes(StandardCharsets.UTF_8)
            )
        );
        try (CloseableHttpResponse response = this.http.execute(request)) {
            return Json.createReader(new StringReader(EntityUtils.toString(response.getEntity())))
                .readObject().getString("token");
        } catch (final IOException err) {
            throw new UncheckedIOException(err);
        }
    }

    private String get(final String line, final String token) {
        final HttpGet request = new HttpGet(
            String.format("http://localhost:%d%s", this.port, line)
        );
        request.addHeader(HttpHeader.ACCEPT.toString(), MimeTypes.Type.APPLICATION_JSON.asString());
        request.addHeader(HttpHeader.AUTHORIZATION.toString(), token);
        try (CloseableHttpResponse response = this.http.execute(request)) {
            return EntityUtils.toString(response.getEntity());
        } catch (final IOException err) {
            throw new UncheckedIOException(err);
        }
    }

    private int head(final String line, final String token) {
        final HttpHead request = new HttpHead(
            String.format("http://localhost:%d%s", this.port, line)
        );
        request.addHeader(HttpHeader.AUTHORIZATION.toString(), token);
        try (CloseableHttpResponse response = this.http.execute(request)) {
            return response.getStatusLine().getStatusCode();
        } catch (final IOException err) {
            throw new UncheckedIOException(err);
        }
    }

    private int put(final String line, final String token, final String body) {
        final HttpPut request = new HttpPut(
            String.format("http://localhost:%d%s", this.port, line)
        );
        request.addHeader(HttpHeader.AUTHORIZATION.toString(), token);
        request.addHeader(
            HttpHeader.CONTENT_TYPE.toString(),
            MimeTypes.Type.APPLICATION_JSON.asString()
        );
        request.setEntity(new ByteArrayEntity(body.getBytes(StandardCharsets.UTF_8)));
        try (CloseableHttpResponse response = this.http.execute(request)) {
            return response.getStatusLine().getStatusCode();
        } catch (final IOException err) {
            throw new UncheckedIOException(err);
        }
    }

    private int delete(final String line, final String token) {
        final HttpDelete request = new HttpDelete(
            String.format("http://localhost:%d%s", this.port, line)
        );
        request.addHeader(HttpHeader.AUTHORIZATION.toString(), token);
        try (CloseableHttpResponse response = this.http.execute(request)) {
            return response.getStatusLine().getStatusCode();
        } catch (final IOException err) {
            throw new UncheckedIOException(err);
        }
    }
}

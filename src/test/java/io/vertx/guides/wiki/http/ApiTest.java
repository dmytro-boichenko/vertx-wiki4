package io.vertx.guides.wiki.http;

import io.vertx.core.DeploymentOptions;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.client.HttpResponse;
import io.vertx.ext.web.client.WebClient;
import io.vertx.ext.web.client.WebClientOptions;
import io.vertx.ext.web.codec.BodyCodec;
import io.vertx.guides.wiki.database.WikiDatabaseVerticle;
import io.vertx.junit5.Checkpoint;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

@ExtendWith(VertxExtension.class)
class ApiTest {

    private WebClient webClient;

    @Test
    public void play_with_api(VertxTestContext context) throws InterruptedException {
        JsonObject page = new JsonObject()
            .put("name", "Sample")
            .put("markdown", "# A page");

        Promise<HttpResponse<JsonObject>> postPagePromise = Promise.promise();
        webClient.post("/api/pages")
            .as(BodyCodec.jsonObject())
            .sendJsonObject(page, postPagePromise);

        Future<HttpResponse<JsonObject>> getPageFuture = postPagePromise.future().compose(resp -> {
            Promise<HttpResponse<JsonObject>> promise = Promise.promise();
            webClient.get("/api/pages")
                .as(BodyCodec.jsonObject())
                .send(promise);
            return promise.future();
        });

        Future<Integer> getPagesFuture = getPageFuture.compose(resp -> {
            JsonArray array = resp.body().getJsonArray("pages");
            assertThat(array.size()).isEqualTo(1);
            JsonObject content = array.getJsonObject(0);
            Integer id = content.getInteger("id");
            assertThat(id).isEqualTo(0);
            assertThat(content.getString("name")).isEqualTo("Sample");
            assertThat(content.getString("markdown")).isNull();

            return Future.succeededFuture(id);
        });

        Future<HttpResponse<JsonObject>> getPageByIdFuture = getPagesFuture.compose(id -> {
            Promise<HttpResponse<JsonObject>> promise = Promise.promise();

            webClient.get("/api/pages/" + id)
                .as(BodyCodec.jsonObject())
                .send(promise);

            return promise.future();
        });

        Future<HttpResponse<JsonObject>> updatePageFuture = getPageByIdFuture.compose(resp -> {
            JsonObject content = resp.body().getJsonObject("page");
            Integer id = content.getInteger("id");
            assertThat(id).isEqualTo(0);
            assertThat(content.getString("name")).isEqualTo("Sample");
            assertThat(content.getString("markdown")).isEqualTo("# A page");

            assertThat(resp.body().getBoolean("success")).isTrue();

            Promise<HttpResponse<JsonObject>> promise = Promise.promise();
            JsonObject data = new JsonObject()
                .put("id", id)
                .put("markdown", "Oh Yeah!");
            webClient.put("/api/pages/" + id)
                .as(BodyCodec.jsonObject())
                .sendJsonObject(data, promise);
            return promise.future();
        });

        Future<HttpResponse<JsonObject>> getPageByIdAfterUpdateFuture = updatePageFuture.compose(reply -> {
            Promise<HttpResponse<JsonObject>> promise = Promise.promise();

            webClient.get("/api/pages/" + 0)
                .as(BodyCodec.jsonObject())
                .send(promise);

            return promise.future();
        });

        Future<HttpResponse<JsonObject>> deletePageFuture = getPageByIdAfterUpdateFuture.compose(resp -> {
            JsonObject content = resp.body().getJsonObject("page");
            Integer id = content.getInteger("id");
            assertThat(id).isEqualTo(0);
            assertThat(content.getString("name")).isEqualTo("Sample");
            assertThat(content.getString("markdown")).isEqualTo("Oh Yeah!");

            assertThat(resp.body().getBoolean("success")).isTrue();
            Promise<HttpResponse<JsonObject>> promise = Promise.promise();
            webClient.delete("/api/pages/0")
                .as(BodyCodec.jsonObject())
                .send(promise);
            return promise.future();
        });

        deletePageFuture.onComplete(ar -> {
            if (ar.succeeded()) {
                assertThat(ar.result().body().getBoolean("success")).isTrue();
                context.completeNow();
            } else {
                context.failNow(ar.cause());
            }
        });

        context.awaitCompletion(5000, TimeUnit.MILLISECONDS);
    }


    @BeforeEach
    void prepare(Vertx vertx, VertxTestContext context) {
        JsonObject dbConf = new JsonObject()
            .put(WikiDatabaseVerticle.CONFIG_WIKIDB_JDBC_URL, "jdbc:hsqldb:mem:testdb;shutdown=true")
            .put(WikiDatabaseVerticle.CONFIG_WIKIDB_JDBC_MAX_POOL_SIZE, 4);

        vertx.deployVerticle(new WikiDatabaseVerticle(),
            new DeploymentOptions().setConfig(dbConf), context.completing());

        vertx.deployVerticle(new HttpServerVerticle(), context.completing());

        webClient = WebClient.create(vertx, new WebClientOptions()
            .setDefaultHost("localhost")
            .setDefaultPort(8080));
    }

    @AfterEach
    void finish(Vertx vertx, VertxTestContext context) {
        vertx.close(context.completing());
    }

}

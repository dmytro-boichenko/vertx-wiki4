package io.vertx.guides.wiki.http;

import com.github.rjeschke.txtmark.Processor;
import io.vertx.core.AsyncResult;
import io.vertx.core.Promise;
import io.vertx.core.json.JsonObject;
import io.vertx.guides.wiki.database.WikiDatabaseService;
import io.vertx.reactivex.core.AbstractVerticle;
import io.vertx.reactivex.core.http.HttpServer;
import io.vertx.reactivex.ext.web.Router;
import io.vertx.reactivex.ext.web.RoutingContext;
import io.vertx.reactivex.ext.web.handler.BodyHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

public class HttpServerVerticle extends AbstractVerticle {

    private static final Logger LOGGER = LoggerFactory.getLogger(HttpServerVerticle.class);

    public static final String CONFIG_HTTP_SERVER_PORT = "http.server.port";
    public static final String CONFIG_WIKIDB_QUEUE = "wikidb.queue";

    private WikiDatabaseService dbService;

    @Override
    public void start(Promise<Void> promise) {
        String wikiDbQueue = config().getString(CONFIG_WIKIDB_QUEUE, CONFIG_WIKIDB_QUEUE);
        dbService = WikiDatabaseService.createProxy(vertx.getDelegate(), wikiDbQueue);

        HttpServer server = vertx.createHttpServer();

        Router router = Router.router(vertx);
        router.mountSubRouter("/api", apiRouter());

        int portNumber = config().getInteger(CONFIG_HTTP_SERVER_PORT, 8080);
        server
            .requestHandler(router)
            .listen(portNumber, ar -> {
                if (ar.succeeded()) {
                    LOGGER.info("HTTP server running on port " + portNumber);
                    promise.complete();
                } else {
                    LOGGER.error("Could not start a HTTP server", ar.cause());
                    promise.fail(ar.cause());
                }
            });
    }


    private Router apiRouter() {
        Router apiRouter = Router.router(vertx);
        apiRouter.get("/pages").handler(this::apiRoot);
        apiRouter.get("/pages/:id").handler(this::apiGetPage);
        apiRouter.post().handler(BodyHandler.create());
        apiRouter.post("/pages").handler(this::apiCreatePage);
        apiRouter.put().handler(BodyHandler.create());
        apiRouter.put("/pages/:id").handler(this::apiUpdatePage);
        apiRouter.delete("/pages/:id").handler(this::apiDeletePage);
        return apiRouter;
    }

    private void apiRoot(RoutingContext context) {
        dbService.fetchAllPagesData(reply -> {
            JsonObject response = new JsonObject();
            if (reply.succeeded()) {
                List<JsonObject> pages = reply.result()
                    .stream()
                    .map(obj -> new JsonObject()
                        .put("id", obj.getInteger("ID"))
                        .put("name", obj.getString("NAME")))
                    .collect(Collectors.toList());
                response
                    .put("success", true)
                    .put("pages", pages);
                context.response().setStatusCode(200);
                context.response().putHeader("Content-Type", "application/json");
                context.response().end(response.encode());
            } else {
                response
                    .put("success", false)
                    .put("error", reply.cause().getMessage());
                context.response().setStatusCode(500);
                context.response().putHeader("Content-Type", "application/json");
                context.response().end(response.encode());
            }
        });
    }

    private void apiGetPage(RoutingContext context) {
        int id = Integer.parseInt(context.request().getParam("id"));
        dbService.fetchPageById(id, reply -> {
            JsonObject response = new JsonObject();
            if (reply.succeeded()) {
                JsonObject dbObject = reply.result();
                if (dbObject.getBoolean("found")) {
                    JsonObject payload = new JsonObject()
                        .put("name", dbObject.getString("name"))
                        .put("id", dbObject.getInteger("id"))
                        .put("markdown", dbObject.getString("content"))
                        .put("html", Processor.process(dbObject.getString("content")));
                    response
                        .put("success", true)
                        .put("page", payload);
                    context.response().setStatusCode(200);
                } else {
                    context.response().setStatusCode(404);
                    response
                        .put("success", false)
                        .put("error", "There is no page with ID " + id);
                }
            } else {
                response
                    .put("success", false)
                    .put("error", reply.cause().getMessage());
                context.response().setStatusCode(500);
            }
            context.response().putHeader("Content-Type", "application/json");
            context.response().end(response.encode());
        });
    }

    private void apiCreatePage(RoutingContext context) {
        JsonObject page = context.getBodyAsJson();
        if (!validateJsonPageDocument(context, page, "name", "markdown")) {
            return;
        }
        dbService.createPage(page.getString("name"), page.getString("markdown"), reply -> {
            if (reply.succeeded()) {
                context.response().setStatusCode(201);
                context.response().putHeader("Content-Type", "application/json");
                context.response().end(new JsonObject().put("success", true).encode());
            } else {
                context.response().setStatusCode(500);
                context.response().putHeader("Content-Type", "application/json");
                context.response().end(new JsonObject()
                    .put("success", false)
                    .put("error", reply.cause().getMessage()).encode());
            }
        });
    }

    private void apiUpdatePage(RoutingContext context) {
        int id = Integer.parseInt(context.request().getParam("id"));
        JsonObject page = context.getBodyAsJson();
        if (!validateJsonPageDocument(context, page, "markdown")) {
            return;
        }
        dbService.savePage(id, page.getString("markdown"), reply -> handleSimpleDbReply(context, reply));
    }

    private void apiDeletePage(RoutingContext context) {
        int id = Integer.parseInt(context.request().getParam("id"));
        dbService.deletePage(id, reply -> handleSimpleDbReply(context, reply));
    }

    private boolean validateJsonPageDocument(RoutingContext context, JsonObject page, String... expectedKeys) {
        if (!Arrays.stream(expectedKeys).allMatch(page::containsKey)) {
            LOGGER.error("Bad page creation JSON payload: " + page.encodePrettily() + " from " + context.request().remoteAddress());
            context.response().setStatusCode(400);
            context.response().putHeader("Content-Type", "application/json");
            context.response().end(new JsonObject()
                .put("success", false)
                .put("error", "Bad request payload").encode());
            return false;
        }
        return true;
    }

    private void handleSimpleDbReply(RoutingContext context, AsyncResult<Void> reply) {
        if (reply.succeeded()) {
            context.response().setStatusCode(200);
            context.response().putHeader("Content-Type", "application/json");
            context.response().end(new JsonObject().put("success", true).encode());
        } else {
            context.response().setStatusCode(500);
            context.response().putHeader("Content-Type", "application/json");
            context.response().end(new JsonObject()
                .put("success", false)
                .put("error", reply.cause().getMessage()).encode());
        }
    }

}

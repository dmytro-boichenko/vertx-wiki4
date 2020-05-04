package io.vertx.guides.wiki.http;

import com.github.rjeschke.txtmark.Processor;
import io.vertx.core.Promise;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.bridge.PermittedOptions;
import io.vertx.ext.web.handler.sockjs.BridgeOptions;
import io.vertx.guides.wiki.database.reactivex.WikiDatabaseService;
import io.vertx.reactivex.core.AbstractVerticle;
import io.vertx.reactivex.core.http.HttpServer;
import io.vertx.reactivex.ext.web.Router;
import io.vertx.reactivex.ext.web.RoutingContext;
import io.vertx.reactivex.ext.web.handler.BodyHandler;
import io.vertx.reactivex.ext.web.handler.StaticHandler;
import io.vertx.reactivex.ext.web.handler.sockjs.SockJSHandler;
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
        dbService = WikiDatabaseService
            .newInstance(io.vertx.guides.wiki.database.WikiDatabaseService
                .createProxy(vertx.getDelegate(), wikiDbQueue));

        HttpServer server = vertx.createHttpServer();

        Router router = Router.router(vertx);
        router.route().handler(BodyHandler.create());

        SockJSHandler sockJSHandler = SockJSHandler.create(vertx);
        BridgeOptions bridgeOptions = new BridgeOptions()
            .addInboundPermitted(new PermittedOptions().setAddress("app.markdown"))
            .addOutboundPermitted(new PermittedOptions().setAddress("page.saved"));
        sockJSHandler.bridge(bridgeOptions);
        router.route("/eventbus/*").handler(sockJSHandler);

        vertx.eventBus().<String>consumer("app.markdown", msg -> {
            String html = Processor.process(msg.body());
            msg.reply(html);
        });

        router.mountSubRouter("/api", apiRouter());
        router.mountSubRouter("/app", appRouter());
        router.get("/").handler(context -> context.reroute("/app/index.html"));

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
        Router router = Router.router(vertx);
        router.get("/pages").handler(this::apiRoot);
        router.get("/pages/:id").handler(this::apiGetPage);
        router.post().handler(BodyHandler.create());
        router.post("/pages").handler(this::apiCreatePage);
        router.put().handler(BodyHandler.create());
        router.put("/pages/:id").handler(this::apiUpdatePage);
        router.delete("/pages/:id").handler(this::apiDeletePage);
        return router;
    }

    private Router appRouter() {
        Router router = Router.router(vertx);
        router.get("/*").handler(StaticHandler.create().setCachingEnabled(false));
        router.post("/markdown").handler(context -> {
            String html = Processor.process(context.getBodyAsString());
            context.response()
                .putHeader("Content-Type", "text/html")
                .setStatusCode(200)
                .end(html);
        });
        return router;
    }

    private void apiRoot(RoutingContext context) {
        dbService.rxFetchAllPagesData().subscribe(
            reply -> {
                List<JsonObject> pages = reply
                    .stream()
                    .map(obj -> new JsonObject()
                        .put("id", obj.getInteger("ID"))
                        .put("name", obj.getString("NAME")))
                    .collect(Collectors.toList());

                apiResponse(context, 200, "pages", pages);
            },
            e -> apiFailure(context, e));
    }

    private void apiGetPage(RoutingContext context) {
        int id = Integer.parseInt(context.request().getParam("id"));
        dbService.rxFetchPageById(id).subscribe(
            obj -> {
                if (obj.getBoolean("found")) {
                    JsonObject payload = new JsonObject()
                        .put("name", obj.getString("name"))
                        .put("id", obj.getInteger("id"))
                        .put("markdown", obj.getString("content"))
                        .put("html", Processor.process(obj.getString("content")));

                    apiResponse(context, 200, "page", payload);
                } else {
                    apiFailure(context, 404, "There is no page with ID " + id);
                }
            },
            e -> apiFailure(context, e));
    }

    private void apiCreatePage(RoutingContext context) {
        JsonObject page = context.getBodyAsJson();
        if (!validateJsonPageDocument(context, page, "name", "markdown")) {
            return;
        }
        dbService.rxCreatePage(page.getString("name"), page.getString("markdown")).subscribe(
            () -> apiResponse(context, 201, null, null),
            t -> apiFailure(context, t));
    }

    private void apiUpdatePage(RoutingContext context) {
        int id = Integer.parseInt(context.request().getParam("id"));
        JsonObject page = context.getBodyAsJson();
        if (!validateJsonPageDocument(context, page, "markdown")) {
            return;
        }
        dbService.rxSavePage(id, page.getString("markdown"))
            .doOnComplete(() -> {
                JsonObject event = new JsonObject()
                    .put("id", id)
                    .put("client", page.getString("client"));
                vertx.eventBus().publish("page.saved", event);
            })
            .subscribe(() -> apiResponse(context, 200, null, null), t -> apiFailure(context, t));
    }

    private void apiDeletePage(RoutingContext context) {
        int id = Integer.parseInt(context.request().getParam("id"));
        dbService.rxDeletePage(id).subscribe(
            () -> apiResponse(context, 200, null, null),
            t -> apiFailure(context, t));
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

    private void apiResponse(RoutingContext context, int statusCode, String jsonField, Object jsonData) {
        context.response().setStatusCode(statusCode);
        context.response().putHeader("Content-Type", "application/json");
        JsonObject wrapped = new JsonObject().put("success", true);
        if (jsonField != null && jsonData != null) {
            wrapped.put(jsonField, jsonData);
        }
        context.response().end(wrapped.encode());
    }

    private void apiFailure(RoutingContext context, Throwable t) {
        apiFailure(context, 500, t.getMessage());
    }

    private void apiFailure(RoutingContext context, int statusCode, String error) {
        context.response().setStatusCode(statusCode);
        context.response().putHeader("Content-Type", "application/json");
        context.response().end(new JsonObject()
            .put("success", false)
            .put("error", error).encode());
    }

}

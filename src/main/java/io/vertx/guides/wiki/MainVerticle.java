package io.vertx.guides.wiki;

import io.reactivex.disposables.Disposable;
import io.vertx.core.DeploymentOptions;
import io.vertx.core.Promise;
import io.vertx.guides.wiki.db.WikiDatabaseVerticle;
import io.vertx.guides.wiki.http.HttpServerVerticle;
import io.vertx.reactivex.core.AbstractVerticle;

public class MainVerticle extends AbstractVerticle {

    private Disposable init;

    @Override
    public void start(Promise<Void> promise) {
        init = vertx
            .rxDeployVerticle(new WikiDatabaseVerticle())
            .flatMap(id -> vertx.rxDeployVerticle(new HttpServerVerticle(), new DeploymentOptions().setInstances(2)))
            .subscribe(id -> promise.complete(), promise::fail);
    }

    @Override
    public void stop(Promise<Void> promise) {
        init.dispose();
    }
}

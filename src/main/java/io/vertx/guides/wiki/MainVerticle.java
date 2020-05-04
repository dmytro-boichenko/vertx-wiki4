package io.vertx.guides.wiki;

import io.reactivex.disposables.Disposable;
import io.vertx.core.DeploymentOptions;
import io.vertx.core.Promise;
import io.vertx.guides.wiki.database.WikiDatabaseVerticle;
import io.vertx.guides.wiki.http.HttpServerVerticle;
import io.vertx.reactivex.core.AbstractVerticle;

public class MainVerticle extends AbstractVerticle {

    private Disposable init;

    @Override
    public void start(Promise<Void> promise) {
        init = vertx
            .rxDeployVerticle(new WikiDatabaseVerticle())
//            .flatMap(id -> vertx.rxDeployVerticle(new HttpServerVerticle(), new DeploymentOptions().setInstances(2)))   // fails with "Can't specify > 1 instances for already created verticle"
//            .flatMap(id -> vertx.rxDeployVerticle(HttpServerVerticle.class.getCanonicalName(), new DeploymentOptions().setInstances(2)))  // works
            .flatMap(id -> vertx.rxDeployVerticle(HttpServerVerticle::new, new DeploymentOptions().setInstances(2)))  // works, because new instance is created via supplier
            .subscribe(id -> promise.complete(), promise::fail);
    }

    @Override
    public void stop(Promise<Void> promise) {
        init.dispose();
    }
}

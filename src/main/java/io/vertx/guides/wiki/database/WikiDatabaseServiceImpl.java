package io.vertx.guides.wiki.database;

import io.reactivex.Single;
import io.vertx.core.AsyncResult;
import io.vertx.core.Handler;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.sql.ResultSet;
import io.vertx.reactivex.CompletableHelper;
import io.vertx.reactivex.MaybeHelper;
import io.vertx.reactivex.SingleHelper;
import io.vertx.reactivex.ext.jdbc.JDBCClient;
import io.vertx.reactivex.ext.sql.SQLClientHelper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

public class WikiDatabaseServiceImpl implements WikiDatabaseService {

    private static final Logger LOGGER = LoggerFactory.getLogger(WikiDatabaseServiceImpl.class);

    private final Map<SqlQuery, String> sqlQueries;
    private final JDBCClient dbClient;

    public WikiDatabaseServiceImpl(io.vertx.ext.jdbc.JDBCClient dbClient,
                                   Map<SqlQuery, String> sqlQueries,
                                   Handler<AsyncResult<WikiDatabaseService>> readyHandler) {
        this.sqlQueries = sqlQueries;
        this.dbClient = new JDBCClient(dbClient);

        SQLClientHelper.usingConnectionSingle(this.dbClient,
            conn -> conn
                .rxExecute(sqlQueries.get(SqlQuery.CREATE_PAGES_TABLE))
                .doOnComplete(() -> LOGGER.info("Database successfully prepared"))
                .doOnError(e -> LOGGER.error("Database preparation error", e))
                .andThen(Single.just(this)))
            .subscribe(SingleHelper.toObserver(readyHandler));
    }

    @Override
    public WikiDatabaseService fetchAllPages(Handler<AsyncResult<JsonArray>> resultHandler) {
        dbClient.rxQuery(sqlQueries.get(SqlQuery.ALL_PAGES))
            .map(rs -> new JsonArray(rs.getResults()
                .stream()
                .map(json -> json.getString(0))
                .sorted()
                .collect(Collectors.toList())))
            .doOnError(e -> LOGGER.error("Database query error", e))
            .subscribe(SingleHelper.toObserver(resultHandler));
        return this;
    }

    @Override
    public WikiDatabaseService fetchAllPagesData(Handler<AsyncResult<List<JsonObject>>> resultHandler) {
        dbClient.rxQuery(sqlQueries.get(SqlQuery.ALL_PAGES_DATA))
            .map(ResultSet::getRows)
            .subscribe(SingleHelper.toObserver(resultHandler));
        return this;
    }

    @Override
    public WikiDatabaseService fetchPage(String name, Handler<AsyncResult<JsonObject>> resultHandler) {
        JsonArray params = new JsonArray().add(name);

        dbClient.rxQueryWithParams(sqlQueries.get(SqlQuery.GET_PAGE), params)
            .map(rs -> {
                JsonObject response = new JsonObject();
                if (rs.getNumRows() == 0) {
                    response.put("found", false);
                } else {
                    response.put("found", true);
                    JsonArray row = rs.getResults().get(0);
                    response.put("id", row.getInteger(0));
                    response.put("rawContent", row.getString(1));
                }
                return response;
            })
            .doOnError(e -> LOGGER.error("Database query error", e))
            .subscribe(SingleHelper.toObserver(resultHandler));
        return this;
    }

    @Override
    public WikiDatabaseService fetchPageById(int id, Handler<AsyncResult<JsonObject>> resultHandler) {
        JsonArray params = new JsonArray().add(id);

        dbClient.rxQuerySingleWithParams(sqlQueries.get(SqlQuery.GET_PAGE_BY_ID), params)
            .map(row -> {
                JsonObject response = new JsonObject();
                if (row == null) {
                    response.put("found", false);
                } else {
                    response.put("found", true);
                    response.put("id", id);
                    response.put("id", row.getInteger(0));
                    response.put("name", row.getString(1));
                    response.put("content", row.getString(2));
                }
                return response;
            })
            .doOnError(e -> LOGGER.error("Database query error", e))
            .subscribe(MaybeHelper.toObserver(resultHandler));
        return this;
    }

    @Override
    public WikiDatabaseService createPage(String title, String markdown, Handler<AsyncResult<Void>> resultHandler) {
        JsonArray data = new JsonArray()
            .add(title)
            .add(markdown);

        dbClient.rxUpdateWithParams(sqlQueries.get(SqlQuery.CREATE_PAGE), data)
            .ignoreElement()
            .doOnError(e -> LOGGER.error("Database update error", e))
            .subscribe(CompletableHelper.toObserver(resultHandler));
        return this;
    }

    @Override
    public WikiDatabaseService savePage(int id, String markdown, Handler<AsyncResult<Void>> resultHandler) {
        JsonArray data = new JsonArray()
            .add(markdown)
            .add(id);

        dbClient.rxUpdateWithParams(sqlQueries.get(SqlQuery.SAVE_PAGE), data)
            .ignoreElement()
            .doOnError(e -> LOGGER.error("Database update error", e))
            .subscribe(CompletableHelper.toObserver(resultHandler));
        return this;
    }

    @Override
    public WikiDatabaseService deletePage(int id, Handler<AsyncResult<Void>> resultHandler) {
        JsonArray data = new JsonArray().add(id);

        dbClient.rxUpdateWithParams(sqlQueries.get(SqlQuery.DELETE_PAGE), data)
            .ignoreElement()
            .doOnError(e -> LOGGER.error("Database update error", e))
            .subscribe(CompletableHelper.toObserver(resultHandler));
        return this;
    }
}

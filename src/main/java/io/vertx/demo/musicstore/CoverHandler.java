/*
 * Copyright 2017 Red Hat, Inc.
 *
 * Red Hat licenses this file to you under the Apache License, version 2.0
 * (the "License"); you may not use this file except in compliance with the
 * License.  You may obtain a copy of the License at:
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.  See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package io.vertx.demo.musicstore;

import io.vertx.core.Handler;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.rxjava.core.buffer.Buffer;
import io.vertx.rxjava.core.shareddata.LocalMap;
import io.vertx.rxjava.ext.jdbc.JDBCClient;
import io.vertx.rxjava.ext.sql.SQLConnection;
import io.vertx.rxjava.ext.sql.SQLRowStream;
import io.vertx.rxjava.ext.web.RoutingContext;
import io.vertx.rxjava.ext.web.client.HttpResponse;
import io.vertx.rxjava.ext.web.client.WebClient;
import io.vertx.rxjava.ext.web.codec.BodyCodec;
import rx.Single;

import java.util.Properties;

/**
 * @author Thomas Segismont
 */
public class CoverHandler implements Handler<RoutingContext> {

  private final JDBCClient dbClient;
  private final String findAlbumById;
  private final WebClient webClient;

  public CoverHandler(JDBCClient dbClient, Properties sqlQueries, WebClient webClient) {
    this.dbClient = dbClient;
    findAlbumById = sqlQueries.getProperty("findAlbumById");
    this.webClient = webClient;
  }

  @Override
  public void handle(RoutingContext rc) {
    Long albumId = PathUtil.parseLongParam(rc.pathParam("albumId"));
    if (albumId == null) {
      rc.next();
      return;
    }

    // Currently there's an issue with rx.Buffer as value type, so workaround with core Buffer
    LocalMap<Long, io.vertx.core.buffer.Buffer> covers = rc.vertx().sharedData().getLocalMap("covers");
    io.vertx.core.buffer.Buffer cached = covers.get(albumId);
    if (cached != null) {
      rc.response().end(Buffer.newInstance(cached));
      return;
    }

    download(albumId)
      .doOnSuccess(buffer -> covers.put(albumId, buffer.getDelegate()))
      .subscribe(rc.response()::end, rc::fail);
  }

  private Single<Buffer> download(Long albumId) {
    return dbClient.rxGetConnection().flatMap(sqlConnection -> {
      return findAlbum(sqlConnection, albumId)
        .doAfterTerminate(sqlConnection::close)
        .flatMap(album -> {
          String mbAlbumId = album.getString("mbAlbumId");
          if (mbAlbumId == null) {
            return Single.error(new RuntimeException("Music Brainz Album Id not found"));
          }
          return webClient
            .getAbs("http://coverartarchive.org")
            .uri("/release/" + mbAlbumId + "/front")
            .as(BodyCodec.buffer())
            .rxSend();
        }).map(HttpResponse::body);
    });
  }

  private Single<JsonObject> findAlbum(SQLConnection sqlConnection, Long albumId) {
    return sqlConnection.rxQueryStreamWithParams(findAlbumById, new JsonArray().add(albumId))
      .flatMapObservable(SQLRowStream::toObservable)
      .toSingle()
      .map(row -> new JsonObject()
        .put("id", albumId)
        .put("title", row.getString(0))
        .put("mbAlbumId", row.getString(1)));
  }
}

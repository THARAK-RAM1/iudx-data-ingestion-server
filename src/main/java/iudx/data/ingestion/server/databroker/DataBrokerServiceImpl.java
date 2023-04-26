package iudx.data.ingestion.server.databroker;

import static iudx.data.ingestion.server.databroker.util.Constants.*;

import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import io.vertx.core.AsyncResult;
import io.vertx.core.Future;
import io.vertx.core.Handler;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.json.JsonObject;
import io.vertx.rabbitmq.RabbitMQClient;
import io.vertx.rabbitmq.RabbitMQOptions;
import iudx.data.ingestion.server.databroker.util.Util;
import java.util.concurrent.TimeUnit;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

public class DataBrokerServiceImpl implements DataBrokerService {

  private static final Logger LOGGER = LogManager.getLogger(DataBrokerServiceImpl.class);
  private final RabbitClient rabbitClient;
  private final String dataBrokerVhost;
  private final RabbitMQClient client;
  private final Cache<String, Boolean> exchangeListCache =
      CacheBuilder.newBuilder().maximumSize(1000)
          .expireAfterAccess(CACHE_TIMEOUT_AMOUNT, TimeUnit.MINUTES).build();

  public DataBrokerServiceImpl(Vertx vertx, RabbitMQClient client, RabbitWebClient rabbitWebClient,
                               String dataBrokerVhost, RabbitMQOptions config,
                               String vhostForAuditing) {
    this.rabbitClient = new RabbitClient(client, rabbitWebClient);
    this.dataBrokerVhost = dataBrokerVhost;
    config.setVirtualHost(vhostForAuditing);
    this.client = RabbitMQClient.create(vertx, config);
    rabbitClient.populateExchangeCache(dataBrokerVhost, exchangeListCache);
  }

  @Override
  public DataBrokerService publishData(JsonObject request,
                                       Handler<AsyncResult<JsonObject>> handler) {
    // TODO Auto-generated method stub
    LOGGER.debug("Info : DataBrokerServiceImpl#publishData() started");
    if (request != null && !request.isEmpty()) {
      JsonObject metaData = Util.getMetadata(request);
      String exchange = metaData.getString(EXCHANGE_NAME);
      Boolean doesExchangeExist = exchangeListCache.getIfPresent(exchange);
      rabbitClient.getExchange(exchange, dataBrokerVhost, doesExchangeExist).compose(ar -> {
        Boolean exchangeFound = ar.getBoolean(DOES_EXCHANGE_EXIST);
        if (!exchangeFound) {
          return Future.failedFuture("Bad Request: Resource ID does not exist");
        }
        exchangeListCache.put(exchange, true);
        return rabbitClient.publishMessage(request, metaData);
      }).onSuccess(ar -> {
        LOGGER.debug("Message published Successfully");
        handler.handle(Future.succeededFuture(new JsonObject().put(TYPE, SUCCESS)));
      }).onFailure(ar -> {
        LOGGER.fatal(ar);
        handler.handle(Future.succeededFuture(
            new JsonObject().put(TYPE, FAILURE).put(ERROR_MESSAGE, ar.getLocalizedMessage())));
      });
    } else {
      handler.handle(Future.succeededFuture(new JsonObject().put(TYPE, FAILURE)
          .put(ERROR_MESSAGE, "Bad Request: Request Json empty")));
    }
    return this;
  }

  @Override
  public DataBrokerService ingestDataPost(JsonObject request,
                                          Handler<AsyncResult<JsonObject>> handler) {
    LOGGER.debug("Info : DataBrokerServiceImpl#ingestData() started");
    if (request != null && !request.isEmpty()) {
      JsonObject object = new JsonObject().put(ERROR, null);
      JsonObject metaData = Util.getMetadata(request);
      String exchangeName = metaData.getString(EXCHANGE_NAME);
      rabbitClient.getQueue(request, dataBrokerVhost).compose(ar -> {
        LOGGER.debug("Info: Get queue successful");
        object.mergeIn(metaData).put(QUEUE_NAME, ar.getString(QUEUE_NAME))
            .put(ERROR, "Exchange creation failed");
        return rabbitClient.createExchange(exchangeName, dataBrokerVhost);
      }).compose(ar -> {
        LOGGER.debug("Info: Exchange creation successful");
        object.put(ERROR, "Queue binding failed");
        exchangeListCache.put(exchangeName, true);
        return rabbitClient.bindQueue(object, dataBrokerVhost);
      }).onSuccess(ar -> {
        LOGGER.debug("Info: Queue binding successful");
        LOGGER.debug("Ingest data operation successful");
        handler.handle(Future.succeededFuture(
            new JsonObject().put(TYPE, SUCCESS).put(EXCHANGE_NAME, exchangeName)
                .put(QUEUE_NAME, object.getString(QUEUE_NAME))
                .put(ROUTING_KEY, object.getString(ROUTING_KEY))));
      }).onFailure(ar -> {
        LOGGER.error("Error: {}", object.getString(ERROR));
        LOGGER.fatal("Error: Ingest data operation failed due to {}", ar.getCause().toString());
        handler.handle(Future.failedFuture(ar.getCause()));
      });
    }
    return this;
  }

  @Override
  public DataBrokerService ingestDataDelete(JsonObject request,
                                            Handler<AsyncResult<JsonObject>> handler) {
    LOGGER.debug("Info: DataBrokerServiceImpl#ingestDataDelete() started");
    if (request != null && !request.isEmpty()) {
      JsonObject metaData = Util.getMetadata(request);
      String exchangeName = metaData.getString(EXCHANGE_NAME);
      rabbitClient.deleteExchange(exchangeName, dataBrokerVhost).onSuccess(ar -> {
        LOGGER.debug("Deletion of exchange successful");
        exchangeListCache.invalidate(exchangeName);
        handler.handle(Future.succeededFuture(new JsonObject().mergeIn(ar)));
      }).onFailure(ar -> {
        LOGGER.debug("Could not delete exchange due to: {}", ar.getCause().toString());
        handler.handle(Future.succeededFuture(new JsonObject().put(TYPE, FAILURE)
            .put(ERROR_MESSAGE, ar.getCause().getLocalizedMessage())));
      });
    } else {
      handler.handle(Future.succeededFuture(new JsonObject().put(TYPE, FAILURE)
          .put(ERROR_MESSAGE, "Bad Request: Request Json empty")));
    }
    return this;
  }

  @Override
  public DataBrokerService publishMessage(JsonObject body, String toExchange, String routingKey,
                                          Handler<AsyncResult<JsonObject>> handler) {
    Future<Void> rabbitMqClientStartFuture;
    Buffer buffer = Buffer.buffer(body.toString());

    if (!client.isConnected()) {
      rabbitMqClientStartFuture = client.start();
    } else {
      rabbitMqClientStartFuture = Future.succeededFuture();
    }
    rabbitMqClientStartFuture.compose(
            rabbitStartupFuture -> client.basicPublish(toExchange, routingKey, buffer))
        .onSuccess(successHandler -> {
          handler.handle(Future.succeededFuture());
        }).onFailure(failureHandler -> {
          LOGGER.error(failureHandler);
          handler.handle(Future.failedFuture(failureHandler.getCause()));
        });
    return this;
  }

}

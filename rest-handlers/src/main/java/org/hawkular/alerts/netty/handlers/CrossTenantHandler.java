package org.hawkular.alerts.netty.handlers;

import static io.netty.handler.codec.http.HttpResponseStatus.OK;
import static org.hawkular.alerts.api.json.JsonUtil.toJson;
import static org.hawkular.alerts.netty.util.ResponseUtil.ACCEPT;
import static org.hawkular.alerts.netty.util.ResponseUtil.APPLICATION_JSON;
import static org.hawkular.alerts.netty.util.ResponseUtil.CONTENT_TYPE;
import static org.hawkular.alerts.netty.util.ResponseUtil.checkTenant;
import static org.hawkular.alerts.netty.util.ResponseUtil.extractPaging;
import static org.hawkular.alerts.netty.util.ResponseUtil.getTenants;
import static org.hawkular.alerts.netty.util.ResponseUtil.result;

import java.util.Set;

import org.hawkular.alerts.api.model.event.Alert;
import org.hawkular.alerts.api.model.event.Event;
import org.hawkular.alerts.api.model.paging.Page;
import org.hawkular.alerts.api.model.paging.Pager;
import org.hawkular.alerts.api.services.AlertsCriteria;
import org.hawkular.alerts.api.services.AlertsService;
import org.hawkular.alerts.api.services.EventsCriteria;
import org.hawkular.alerts.engine.StandaloneAlerts;
import org.hawkular.alerts.log.MsgLogger;
import org.hawkular.alerts.netty.RestEndpoint;
import org.hawkular.alerts.netty.RestHandler;
import org.hawkular.alerts.netty.util.ResponseUtil.BadRequestException;
import org.hawkular.alerts.netty.util.ResponseUtil.InternalServerException;

import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;

/**
 * @author Jay Shaughnessy
 * @author Lucas Ponce
 */
@RestEndpoint(path = "/admin")
public class CrossTenantHandler implements RestHandler {
    private static final MsgLogger log = MsgLogger.getLogger(CrossTenantHandler.class);
    private static final String PARAM_WATCH_INTERVAL = "watchInterval";

    AlertsService alertsService;

    public CrossTenantHandler() {
        alertsService = StandaloneAlerts.getAlertsService();
    }

    @Override
    public void initRoutes(String baseUrl, Router router) {
        String path = baseUrl + "/admin";
        router.get(path + "/alerts").handler(this::findAlerts);
        router.get(path + "/events").handler(this::findEvents);
        router.get(path + "/watch/alerts").blockingHandler(this::watchAlerts);
        router.get(path + "/watch/events").blockingHandler(this::watchEvents);
    }

    void findAlerts(RoutingContext routing) {
        routing.vertx()
                .executeBlocking(future -> {
                    String tenantId = checkTenant(routing);
                    Set<String> tenantIds = getTenants(tenantId);
                    try {
                        Pager pager = extractPaging(routing.request().params());
                        AlertsCriteria criteria = AlertsHandler.buildCriteria(routing.request().params());
                        Page<Alert> alertPage = alertsService.getAlerts(tenantIds, criteria, pager);
                        log.debug("Alerts: {}", alertPage);
                        future.complete(alertPage);
                    } catch (IllegalArgumentException e) {
                        throw new BadRequestException("Bad arguments: " + e.getMessage());
                    } catch (Exception e) {
                        log.debug(e.getMessage(), e);
                        throw new InternalServerException(e.toString());
                    }
                }, res -> result(routing, res));
    }

    void findEvents(RoutingContext routing) {
        routing.vertx()
                .executeBlocking(future -> {
                    String tenantId = checkTenant(routing);
                    Set<String> tenantIds = getTenants(tenantId);
                    try {
                        Pager pager = extractPaging(routing.request().params());
                        EventsCriteria criteria = EventsHandler.buildCriteria(routing.request().params());
                        Page<Event> eventPage = alertsService.getEvents(tenantIds, criteria, pager);
                        log.debug("Events: {}", eventPage);
                        future.complete(eventPage);
                    } catch (IllegalArgumentException e) {
                        throw new BadRequestException("Bad arguments: " + e.getMessage());
                    } catch (Exception e) {
                        log.debug(e.getMessage(), e);
                        throw new InternalServerException(e.toString());
                    }
                }, res -> result(routing, res));
    }

    void watchAlerts(RoutingContext routing) {
        String tenantId = checkTenant(routing);
        Set<String> tenantIds = getTenants(tenantId);
        AlertsCriteria criteria = AlertsHandler.buildCriteria(routing.request().params());
        Long watchInterval = null;
        if (routing.request().params().get(PARAM_WATCH_INTERVAL) != null) {
            watchInterval = Long.valueOf(routing.request().params().get(PARAM_WATCH_INTERVAL));
        }
        routing.response()
                .putHeader(ACCEPT, APPLICATION_JSON)
                .putHeader(CONTENT_TYPE, APPLICATION_JSON)
                .setChunked(true)
                .setStatusCode(OK.code());

        AlertsWatcher.AlertsListener listener = alert -> {
            routing.response().write(toJson(alert) + "\r\n");
        };
        String channelId = routing.request().connection().remoteAddress().toString();
        AlertsWatcher watcher = new AlertsWatcher(channelId, listener, tenantIds, criteria, watchInterval);
        watcher.start();
        log.info("AlertsWatcher [{}] created", channelId);
        routing.response().closeHandler(e -> {
            watcher.dispose();
            log.info("AlertsWatcher [{}] finished", channelId);
        });
    }

    void watchEvents(RoutingContext routing) {
        String tenantId = checkTenant(routing);
        Set<String> tenantIds = getTenants(tenantId);
        EventsCriteria criteria = EventsHandler.buildCriteria(routing.request().params());
        Long watchInterval = null;
        if (routing.request().params().get(PARAM_WATCH_INTERVAL) != null) {
            watchInterval = Long.valueOf(routing.request().params().get(PARAM_WATCH_INTERVAL));
        }
        routing.response()
                .putHeader(ACCEPT, APPLICATION_JSON)
                .putHeader(CONTENT_TYPE, APPLICATION_JSON)
                .setChunked(true)
                .setStatusCode(OK.code());
        EventsWatcher.EventsListener listener = event -> {
            routing.response().write(toJson(event) + "\r\n");
        };
        String channelId = routing.request().connection().remoteAddress().toString();
        EventsWatcher watcher = new EventsWatcher(channelId, listener, tenantIds, criteria, watchInterval);
        watcher.start();
        log.info("EventsWatcher [{}] created", channelId);
        routing.response().closeHandler(e -> {
            watcher.dispose();
            log.info("EventsWatcher [{}] finished", channelId);
        });
    }
}
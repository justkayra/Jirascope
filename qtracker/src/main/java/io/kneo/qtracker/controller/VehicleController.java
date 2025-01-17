package io.kneo.qtracker.controller;

import io.kneo.core.controller.AbstractSecuredController;
import io.kneo.core.dto.actions.ActionBox;
import io.kneo.core.dto.cnst.PayloadType;
import io.kneo.core.dto.form.FormPage;
import io.kneo.core.dto.view.View;
import io.kneo.core.dto.view.ViewPage;
import io.kneo.core.localization.LanguageCode;
import io.kneo.core.model.user.IUser;
import io.kneo.core.repository.exception.DocumentHasNotFoundException;
import io.kneo.core.service.UserService;
import io.kneo.core.util.RuntimeUtil;
import io.kneo.qtracker.dto.VehicleDTO;
import io.kneo.qtracker.dto.actions.VehicleActionsFactory;
import io.kneo.qtracker.model.Vehicle;
import io.kneo.qtracker.service.VehicleService;
import io.smallrye.mutiny.Uni;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.List;
import java.util.UUID;

@ApplicationScoped
public class VehicleController extends AbstractSecuredController<Vehicle, VehicleDTO> {

    @Inject
    VehicleService service;

    public VehicleController() {
        super(null);
    }

    public VehicleController(UserService userService) {
        super(userService);
    }

    public void setupRoutes(Router router) {
        router.route(HttpMethod.GET, "/api/:org/vehicles").handler(this::get);
        router.route(HttpMethod.GET, "/api/:org/vehicles/:id").handler(this::getById);
        router.route(HttpMethod.POST, "/api/:org/vehicles/:messenger_type/:id?").handler(this::upsertFromMessenger);
        router.route(HttpMethod.POST, "/api/:org/vehicles/:id?").handler(this::upsert);
        router.route(HttpMethod.DELETE, "/api/:org/vehicles/:id").handler(this::delete);
    }

    private void get(RoutingContext rc) {
        int page = Integer.parseInt(rc.request().getParam("page", "1"));
        int size = Integer.parseInt(rc.request().getParam("size", "10"));
        IUser user = getUser(rc);

        Uni.combine().all().unis(
                service.getAllCount(user),
                service.getAll(size, (page - 1) * size, user)
        ).asTuple().subscribe().with(
                tuple -> {
                    int count = tuple.getItem1();
                    List<VehicleDTO> vehicles = tuple.getItem2();

                    int maxPage = RuntimeUtil.countMaxPage(count, size);

                    ViewPage viewPage = new ViewPage();
                    View<VehicleDTO> dtoEntries = new View<>(vehicles, count, page, maxPage, size);
                    viewPage.addPayload(PayloadType.VIEW_DATA, dtoEntries);

                    ActionBox actions = VehicleActionsFactory.getViewActions(user.getActivatedRoles());
                    viewPage.addPayload(PayloadType.CONTEXT_ACTIONS, actions);

                    rc.response().setStatusCode(200).end(JsonObject.mapFrom(viewPage).encode());
                },
                failure -> {
                    LOGGER.error("Error processing request: ", failure);
                    rc.response().setStatusCode(500).end("Internal Server Error");
                }
        );
    }

    private void getById(RoutingContext rc) {
        String id = rc.pathParam("id");
        LanguageCode languageCode = LanguageCode.valueOf(rc.request().getParam("lang", LanguageCode.ENG.name()));

        service.getDTO(UUID.fromString(id), getUser(rc), languageCode).subscribe().with(
                vehicle -> {
                    FormPage page = new FormPage();
                    page.addPayload(PayloadType.DOC_DATA, vehicle);
                    page.addPayload(PayloadType.CONTEXT_ACTIONS, new ActionBox());
                    rc.response().setStatusCode(200).end(JsonObject.mapFrom(page).encode());
                },
                failure -> {
                    if (failure instanceof DocumentHasNotFoundException) {
                        rc.response().setStatusCode(404).end("Vehicle not found");
                    } else {
                        LOGGER.error("Error processing request: ", failure);
                        rc.response().setStatusCode(500).end("Internal Server Error");
                    }
                }
        );
    }

    private void upsertFromMessenger(RoutingContext rc) {
        String id = rc.pathParam("id");
        JsonObject jsonObject = rc.body().asJsonObject();
        VehicleDTO dto = jsonObject.mapTo(VehicleDTO.class);
        service.upsert(id, dto, getUser(rc), LanguageCode.ENG)
                .subscribe().with(
                        doc -> {
                            int statusCode = id == null ? 201 : 200;
                            rc.response().setStatusCode(statusCode).end(JsonObject.mapFrom(doc).encode());
                        },
                        rc::fail
                );
    }

    private void upsert(RoutingContext rc) {
        String id = rc.pathParam("id");
        JsonObject jsonObject = rc.body().asJsonObject();
        VehicleDTO dto = jsonObject.mapTo(VehicleDTO.class);
        service.upsert(id, dto, getUser(rc), LanguageCode.ENG)
                .subscribe().with(
                        doc -> {
                            int statusCode = id == null ? 201 : 200;
                            rc.response().setStatusCode(statusCode).end(JsonObject.mapFrom(doc).encode());
                        },
                        rc::fail
                );
    }

    private void delete(RoutingContext rc) {
        String id = rc.pathParam("id");
        service.delete(id, getUser(rc)).subscribe().with(
                count -> {
                    rc.response().setStatusCode(count > 0 ? 204 : 404);
                },
                rc::fail
        );
    }
}
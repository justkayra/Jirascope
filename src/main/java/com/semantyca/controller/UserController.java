package com.semantyca.controller;

import com.semantyca.dto.document.UserDTO;
import com.semantyca.dto.view.ViewPage;
import com.semantyca.model.user.User;
import com.semantyca.repository.exception.DocumentModificationAccessException;
import com.semantyca.service.UserService;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.net.URI;

@Path("/users")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class UserController {

    @Inject
    UserService userService;

    @GET
    @Path("/")
    public Response get()  {
        return Response.ok(new ViewPage(userService.getAll())).build();
    }

    @GET
    @Path("/{id}")
    public Response getById(@PathParam("id") String id)  {
        User user = userService.get(id);
        return Response.ok(user).build();
    }

    @POST
    @Path("/")
    public Response create(UserDTO userDTO) {
        return Response.created(URI.create("/" + userService.add(userDTO))).build();
    }

    @PUT
    @Path("/")
    public Response update(UserDTO userDTO) throws DocumentModificationAccessException {
        return Response.ok(URI.create("/" + userService.update(userDTO).getIdentifier())).build();
    }

    @DELETE
    @Path("/{id}")
    @Produces(MediaType.APPLICATION_JSON)
    public Response deleteWord(@PathParam("id") String id) {
        return Response.ok().build();
    }

}
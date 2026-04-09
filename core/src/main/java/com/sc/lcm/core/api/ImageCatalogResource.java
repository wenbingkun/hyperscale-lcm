package com.sc.lcm.core.api;

import com.sc.lcm.core.service.SatelliteImageCatalogService;
import io.smallrye.mutiny.Uni;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import java.util.List;

@Path("/api/images")
@Produces(MediaType.APPLICATION_JSON)
@RolesAllowed({ "ADMIN", "OPERATOR", "USER" })
public class ImageCatalogResource {

    @Inject
    SatelliteImageCatalogService satelliteImageCatalogService;

    @GET
    public Uni<List<SatelliteImageCatalogService.SatelliteImageCatalogEntry>> listImages(
            @QueryParam("clusterId") String clusterId,
            @QueryParam("onlineOnly") @DefaultValue("true") boolean onlineOnly) {
        return satelliteImageCatalogService.listImages(clusterId, onlineOnly);
    }
}

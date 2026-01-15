package com.sc.lcm.core.api;

import com.sc.lcm.core.domain.Satellite;
import io.smallrye.mutiny.Uni;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import java.util.List;

/**
 * REST API - 使用响应式返回类型
 */
@Path("/satellites")
@Produces(MediaType.APPLICATION_JSON)
public class SatelliteResource {

    @GET
    public Uni<List<Satellite>> list() {
        return Satellite.listAllReactive();
    }
}

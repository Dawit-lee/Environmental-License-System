package com.envpermit.core.permit;

/** Published inside the permit's transaction; lets other modules (notification) follow permits without core depending on them. */
public record PermitChanged(PermitItem permit, boolean deleted) {
}

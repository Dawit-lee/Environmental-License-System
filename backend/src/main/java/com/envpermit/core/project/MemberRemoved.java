package com.envpermit.core.project;

/** Published inside the removal's transaction; lets other modules (obligation tasks) follow it without core depending on them. */
public record MemberRemoved(long projectId, long userId, long actorId) {
}

package com.emporia.execution;

/**
 * Readiness gate for accepting new OMS order commands.
 *
 * <p>This is intentionally narrower than {@link ExecutionVenueGateway}: the
 * OMS ring only needs to know whether a 201 can safely mean "accepted by OMS
 * and eligible for venue handoff". It must not depend on a concrete venue
 * implementation or call venue operations from the intake path.
 */
public interface ExecutionVenueReadiness {
    OrderIntakeReadiness orderIntakeReadiness();
}

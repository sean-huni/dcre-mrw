package za.co.fnb.dcre.mrw;

import org.springframework.jdbc.core.JdbcTemplate;

import java.util.UUID;

/**
 * BDD seeding helpers. man_outbound + the shared core come from MRW's own
 * Liquibase (001-man-outbound.xml / 000-man-core-bootstrap.xml), so this helper
 * only stands up the MRR-owned spine tables (mandate_request_header /
 * mandate_request_entry, NOT in MRW's changelog, exactly as MRV's ManTestTables
 * does) with the columns the MRW read models map, and seeds header + INITIALIZED
 * entry rows. spine_state is inserted as INITIALIZED, the state MIS leaves and MRW
 * transitions to SUBMITTED.
 */
public final class ManTestTables {

    private ManTestTables() {
    }

    public static void createSpine(final JdbcTemplate jdbc) {
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS mandate_request_header (
                    id UUID NOT NULL DEFAULT gen_random_uuid() PRIMARY KEY,
                    version BIGINT NOT NULL DEFAULT 0,
                    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
                    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
                    arrival_id UUID NOT NULL UNIQUE,
                    client_token VARCHAR(16),
                    destination_id VARCHAR(16) NOT NULL,
                    entry_count INT NOT NULL)""");
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS mandate_request_entry (
                    id UUID NOT NULL DEFAULT gen_random_uuid() PRIMARY KEY,
                    version BIGINT NOT NULL DEFAULT 0,
                    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
                    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
                    arrival_id UUID NOT NULL,
                    sequence INT NOT NULL,
                    record_type VARCHAR(2) NOT NULL,
                    action_code VARCHAR(16) NOT NULL,
                    mandate_ref VARCHAR(35) NOT NULL,
                    contract_ref VARCHAR(14),
                    creditor_account VARCHAR(32),
                    debtor_account VARCHAR(32),
                    debtor_branch VARCHAR(16),
                    debtor_name VARCHAR(70),
                    currency VARCHAR(3) NOT NULL,
                    max_collection_amount DECIMAL(18,2) NOT NULL,
                    mndt_req_id VARCHAR(35),
                    spine_state VARCHAR(16) NOT NULL DEFAULT 'RECEIVED',
                    UNIQUE (arrival_id, sequence))""");
    }

    public static void insertHeader(final JdbcTemplate jdbc, final UUID arrival, final String client,
                                    final int entryCount) {
        jdbc.update("""
                INSERT INTO mandate_request_header (arrival_id, client_token, destination_id, entry_count)
                VALUES (?,?,?,?)""", arrival, client, client, entryCount);
    }

    /** An INITIALIZED spine row (MIS-left state) ready for MRW to emit + submit. */
    public static void insertInitialized(final JdbcTemplate jdbc, final UUID arrival, final int sequence,
                                         final String action, final String ref, final String mndtReqId) {
        jdbc.update("""
                INSERT INTO mandate_request_entry (arrival_id, sequence, record_type, action_code, mandate_ref,
                    contract_ref, creditor_account, debtor_account, debtor_branch, debtor_name, currency,
                    max_collection_amount, mndt_req_id, spine_state)
                VALUES (?,?, 'MD', ?,?, 'CTR0001', '6200000021', '6299999999', '250655', 'ACME PTY LTD', 'ZAR',
                    100.00, ?, 'INITIALIZED')""",
                arrival, sequence, action, ref, mndtReqId);
    }
}

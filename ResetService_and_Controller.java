// =====================================================================
// ResetService.java
// =====================================================================
package com.babcock.openfire.plugin.rest.service;

import com.babcock.openfire.plugin.rest.exception.ServiceException;
import org.jivesoftware.openfire.admin.AdminManager;
import org.jivesoftware.openfire.user.UserManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.ws.rs.core.Response;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Destructive full-reset operation: removes all non-admin users, all MUC
 * rooms, and all historical conversation logs.
 *
 * IMPORTANT — READ BEFORE USE:
 * 1. This runs as a single JDBC transaction for true all-or-nothing
 *    atomicity, per requirements. That means it deletes directly via SQL
 *    rather than through Openfire's UserManager/MultiUserChatManager APIs.
 *    Consequence: Openfire's in-memory caches (logged-in sessions, cached
 *    User objects, cached MUC room objects) will NOT automatically reflect
 *    these deletions until the relevant caches are cleared/reloaded — see
 *    invalidateCaches() below. If that cache clear is incomplete for your
 *    Openfire version, a server restart is the safe fallback after running
 *    this endpoint.
 * 2. The exact table list for "others" (rosters, offline messages, vcards,
 *    private storage, security audit log, etc.) is a guess based on
 *    Openfire's standard schema — CONFIRM against your actual DB schema
 *    and installed plugins (e.g. Monitoring plugin tables) before running
 *    this against anything but a throwaway/dev database.
 * 3. There is no confirmation token on the endpoint (per current
 *    requirements) — only normal auth. Treat this endpoint as extremely
 *    sensitive; do not expose it beyond trusted admin tooling.
 */
public class ResetService {

    private static final Logger log = LoggerFactory.getLogger(ResetService.class);

    private static final ResetService INSTANCE = new ResetService();
    public static ResetService getInstance() { return INSTANCE; }
    private ResetService() {}

    // --- Users -----------------------------------------------------
    // TODO: confirm full list of user-related tables in your schema.
    // Standard Openfire tables shown; add/remove based on what's actually
    // populated in your deployment (e.g. ofPrivate, ofVCard may be unused).
    private static final String DELETE_USER_PROPS      = "DELETE FROM ofUserProp WHERE username = ?";
    private static final String DELETE_USER_FLAGS       = "DELETE FROM ofUserFlag WHERE username = ?";
    private static final String DELETE_ROSTER_ITEMS     = "DELETE FROM ofRosterGroups WHERE rosterID IN (SELECT rosterID FROM ofRoster WHERE username = ?)";
    private static final String DELETE_ROSTER           = "DELETE FROM ofRoster WHERE username = ?";
    private static final String DELETE_OFFLINE_MESSAGES = "DELETE FROM ofOffline WHERE username = ?";
    private static final String DELETE_VCARD            = "DELETE FROM ofVCard WHERE username = ?";
    private static final String DELETE_PRIVATE_STORAGE  = "DELETE FROM ofPrivate WHERE username = ?";
    private static final String DELETE_USER             = "DELETE FROM ofUser WHERE username = ?";

    private static final String SELECT_ALL_USERNAMES    = "SELECT username FROM ofUser";

    // --- Rooms / MUC history ----------------------------------------
    // TODO: confirm — if you have the Monitoring plugin installed, its
    // archive tables (ofMucConversationLog is Monitoring-plugin-owned)
    // and any related indexes/attachments tables need to be included too.
    private static final String DELETE_MUC_CONVERSATION_LOG = "DELETE FROM ofMucConversationLog";
    private static final String DELETE_MUC_MEMBER           = "DELETE FROM ofMucMember";
    private static final String DELETE_MUC_AFFILIATION      = "DELETE FROM ofMucAffiliation";
    private static final String DELETE_MUC_ROOM_PROP        = "DELETE FROM ofMucRoomProp";
    private static final String DELETE_MUC_ROOM             = "DELETE FROM ofMucRoom";

    /**
     * Performs the full reset. All-or-nothing: any failure rolls back
     * every change made so far in this call.
     *
     * @return a summary of what was removed, for the response/audit log
     */
    public ResetSummary resetAll() throws ServiceException {

        Set<String> adminUsernames = resolveAdminUsernames();
        log.warn("RESET-ALL requested. Preserving {} admin account(s): {}",
                adminUsernames.size(), adminUsernames);

        Connection conn = null;
        int usersDeleted = 0;
        int roomsDeleted = 0;
        int messagesDeleted = 0;

        try {
            conn = DbConnectionManager.getConnection();
            conn.setAutoCommit(false);

            // ---- 1. Delete non-admin users ----
            List<String> allUsernames = fetchAllUsernames(conn);
            for (String username : allUsernames) {
                if (adminUsernames.contains(username)) {
                    continue; // preserve admin accounts
                }
                deleteUserCascade(conn, username);
                usersDeleted++;
            }

            // ---- 2. Delete MUC conversation history ----
            try (PreparedStatement stmt = conn.prepareStatement(DELETE_MUC_CONVERSATION_LOG)) {
                messagesDeleted = stmt.executeUpdate();
            }

            // ---- 3. Delete rooms (and room-scoped tables) ----
            try (PreparedStatement stmt = conn.prepareStatement(DELETE_MUC_MEMBER)) {
                stmt.executeUpdate();
            }
            try (PreparedStatement stmt = conn.prepareStatement(DELETE_MUC_AFFILIATION)) {
                stmt.executeUpdate();
            }
            try (PreparedStatement stmt = conn.prepareStatement(DELETE_MUC_ROOM_PROP)) {
                stmt.executeUpdate();
            }
            try (PreparedStatement stmt = conn.prepareStatement(DELETE_MUC_ROOM)) {
                roomsDeleted = stmt.executeUpdate();
            }

            conn.commit();

            log.warn("RESET-ALL completed: {} user(s) deleted, {} room(s) deleted, {} message(s) deleted",
                    usersDeleted, roomsDeleted, messagesDeleted);

        } catch (Exception e) {
            log.error("RESET-ALL failed — rolling back all changes", e);
            if (conn != null) {
                try {
                    conn.rollback();
                } catch (Exception rollbackEx) {
                    log.error("Rollback also failed — database may be in an inconsistent state", rollbackEx);
                }
            }
            throw new ServiceException("Reset failed: " + e.getMessage(), Response.Status.INTERNAL_SERVER_ERROR);
        } finally {
            if (conn != null) {
                try {
                    conn.setAutoCommit(true);
                } catch (Exception ignored) {
                }
                DbConnectionManager.closeConnection(conn);
            }
        }

        invalidateCaches();

        return new ResetSummary(usersDeleted, roomsDeleted, messagesDeleted, adminUsernames);
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    /**
     * Uses Openfire's own AdminManager (reads the admin.authorizedJIDs
     * server property) rather than a DB flag, per your requirement that
     * "admin" means users with Openfire's admin/manage-server permission.
     */
    private Set<String> resolveAdminUsernames() {
        Set<String> admins = new HashSet<>();
        try {
            List<?> adminAccounts = AdminManager.getInstance().getAdminAccounts();
            for (Object account : adminAccounts) {
                // AdminManager typically returns JID-like objects; adjust
                // this extraction to match your Openfire version's actual
                // return type (JID vs String vs AdminAccount wrapper).
                admins.add(account.toString());
            }
        } catch (Exception e) {
            log.error("Failed to resolve admin accounts via AdminManager — aborting reset for safety", e);
            throw new IllegalStateException("Could not determine admin accounts; refusing to proceed", e);
        }

        if (admins.isEmpty()) {
            // Safety net: if we can't positively identify at least one
            // admin to preserve, refuse to run rather than risk wiping
            // every account including the operator's own.
            throw new IllegalStateException("No admin accounts resolved — refusing to run reset-all");
        }

        return admins;
    }

    private List<String> fetchAllUsernames(Connection conn) throws Exception {
        List<String> usernames = new java.util.ArrayList<>();
        try (PreparedStatement stmt = conn.prepareStatement(SELECT_ALL_USERNAMES);
             var rs = stmt.executeQuery()) {
            while (rs.next()) {
                usernames.add(rs.getString("username"));
            }
        }
        return usernames;
    }

    private void deleteUserCascade(Connection conn, String username) throws Exception {
        // TODO: confirm this list is complete for your schema/plugins.
        try (PreparedStatement s1 = conn.prepareStatement(DELETE_USER_PROPS)) {
            s1.setString(1, username);
            s1.executeUpdate();
        }
        try (PreparedStatement s2 = conn.prepareStatement(DELETE_USER_FLAGS)) {
            s2.setString(1, username);
            s2.executeUpdate();
        }
        try (PreparedStatement s3 = conn.prepareStatement(DELETE_ROSTER_ITEMS)) {
            s3.setString(1, username);
            s3.executeUpdate();
        }
        try (PreparedStatement s4 = conn.prepareStatement(DELETE_ROSTER)) {
            s4.setString(1, username);
            s4.executeUpdate();
        }
        try (PreparedStatement s5 = conn.prepareStatement(DELETE_OFFLINE_MESSAGES)) {
            s5.setString(1, username);
            s5.executeUpdate();
        }
        try (PreparedStatement s6 = conn.prepareStatement(DELETE_VCARD)) {
            s6.setString(1, username);
            s6.executeUpdate();
        }
        try (PreparedStatement s7 = conn.prepareStatement(DELETE_PRIVATE_STORAGE)) {
            s7.setString(1, username);
            s7.executeUpdate();
        }
        try (PreparedStatement s8 = conn.prepareStatement(DELETE_USER)) {
            s8.setString(1, username);
            s8.executeUpdate();
        }
    }

    /**
     * Best-effort cache invalidation so the running server reflects the
     * reset without requiring a restart. This is NOT part of the SQL
     * transaction (can't be — these are in-memory Java caches, not DB
     * state), so it runs only after a successful commit.
     *
     * TODO: verify these are the correct cache-clearing calls for your
     * Openfire version — API surface varies across versions, and MUC
     * service caches in particular may need clearing per-service if you
     * run multiple MUC services.
     */
    private void invalidateCaches() {
        try {
            UserManager.getInstance().getUserCache().clear();
        } catch (Exception e) {
            log.warn("Failed to clear UserManager cache after reset — a server restart may be needed "
                    + "for all clients to see the reset state", e);
        }

        try {
            // TODO: clear MultiUserChatManager / MUCRoom caches here too.
            // The exact API depends on your Openfire version — e.g.
            // XMPPServer.getInstance().getMultiUserChatManager()
            //     .getMultiUserChatService(...).getChatRoom(...) caches.
            log.warn("MUC room cache invalidation not yet implemented — "
                    + "a server restart is recommended after reset-all until this is wired up.");
        } catch (Exception e) {
            log.warn("Failed to clear MUC caches after reset", e);
        }
    }

    public static class ResetSummary {
        public final int usersDeleted;
        public final int roomsDeleted;
        public final int messagesDeleted;
        public final Set<String> preservedAdmins;

        public ResetSummary(int usersDeleted, int roomsDeleted, int messagesDeleted, Set<String> preservedAdmins) {
            this.usersDeleted = usersDeleted;
            this.roomsDeleted = roomsDeleted;
            this.messagesDeleted = messagesDeleted;
            this.preservedAdmins = preservedAdmins;
        }
    }
}


// =====================================================================
// AdminResetController.java
// =====================================================================
package com.babcock.openfire.plugin.rest.controller;

import com.babcock.openfire.plugin.rest.dto.ErrorResponse;
import com.babcock.openfire.plugin.rest.exception.ServiceException;
import com.babcock.openfire.plugin.rest.service.ResetService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import java.util.LinkedHashMap;
import java.util.Map;

@Path("/admin/reset-all")
@Produces(MediaType.APPLICATION_JSON)
public class AdminResetController {

    private static final Logger log = LoggerFactory.getLogger(AdminResetController.class);

    private final ResetService resetService = ResetService.getInstance();

    @POST
    public Response resetAll() {
        log.warn("RESET-ALL endpoint invoked");

        try {
            ResetService.ResetSummary summary = resetService.resetAll();

            Map<String, Object> body = new LinkedHashMap<>();
            body.put("success", true);
            body.put("usersDeleted", summary.usersDeleted);
            body.put("roomsDeleted", summary.roomsDeleted);
            body.put("messagesDeleted", summary.messagesDeleted);
            body.put("preservedAdmins", summary.preservedAdmins);

            return Response.ok(body).build();

        } catch (ServiceException e) {
            log.error("Reset-all failed", e);
            return Response.status(e.getStatus())
                    .entity(new ErrorResponse(e.getMessage(), "reset-all"))
                    .build();
        } catch (IllegalStateException e) {
            log.error("Reset-all refused to run", e);
            return Response.status(Response.Status.PRECONDITION_FAILED)
                    .entity(new ErrorResponse(e.getMessage(), "reset-all"))
                    .build();
        }
    }
}



COUNT=$(mysql -N -h openfire-mysql -u$MYSQL_USER -p$MYSQL_PASSWORD $MYSQL_DATABASE \
        "SELECT COUNT(*) FROM ofMucService WHERE subdomain='${OPENFIRE_SERVICE}';")

if [ "$COUNT" = "0" ]; then
echo "Creating chat service..."

NEXT_ID=$(mysql -N -h openfire-mysql -u$MYSQL_USER -p$MYSQL_PASSWORD $MYSQL_DATABASE \
        "SELECT COALESCE(MAX(serviceID),0)+1 FROM ofMucService;")

mysql -h openfire-mysql -u$MYSQL_USER -p$MYSQL_PASSWORD $MYSQL_DATABASE <<EOF
INSERT INTO ofMucService (serviceID, subdomain, description, isHidden)
VALUES ($NEXT_ID, '${OPENFIRE_SERVICE}', 'Default chat service', 0);
EOF

echo "Service created."
        else
echo "Service already exists."
fi

# Disable Openfire blog feed
mysql -h openfire-mysql -u$MYSQL_USER -p$MYSQL_PASSWORD $MYSQL_DATABASE <<EOF
INSERT INTO ofProperty (name, propValue)
VALUES ('adminConsole.blog-feed.enabled', 'false')
ON DUPLICATE KEY UPDATE propValue='false';
EOF

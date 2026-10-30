package com.moveinsync.rostersync.it;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.moveinsync.rostersync.security.CurrentUser.Role;
import com.moveinsync.rostersync.security.UserAccountService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Login (DAO) + JWT, role-based access, tenant isolation and non-leaking errors, over real HTTP calls. */
@AutoConfigureMockMvc
class SecurityIT extends IntegrationTestBase {

    @Autowired MockMvc mvc;
    @Autowired UserAccountService users;
    @Autowired ObjectMapper mapper;

    String a, b, s;

    @BeforeEach
    void setUp() {
        a = newTenant();
        b = newTenant();
        s = UUID.randomUUID().toString().substring(0, 6);
        users.createUser("hr-a-" + s, "pw", Role.HR_SYSTEM, a, null);
        users.createUser("admin-a-" + s, "pw", Role.ADMIN, a, null);
        users.createUser("ops-a-" + s, "pw", Role.OPERATOR, a, null);
        users.createUser("emp-a-" + s, "pw", Role.EMPLOYEE, a, "A1");
        users.createUser("hr-b-" + s, "pw", Role.HR_SYSTEM, b, null);
        users.createUser("admin-b-" + s, "pw", Role.ADMIN, b, null);
        users.createUser("ops-b-" + s, "pw", Role.OPERATOR, b, null);
        send(a, create("A1", 1), create("A2", 1));
        send(b, create("B1", 1));
    }

    /** Logs in through the real endpoint and returns the "Authorization" header value. */
    private String token(String user) throws Exception {
        MvcResult r = mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + user + "-" + s + "\",\"password\":\"pw\"}"))
                .andExpect(status().isOk()).andReturn();
        return "Bearer " + json(r).get("accessToken").asText();
    }

    private JsonNode json(MvcResult r) throws Exception {
        return mapper.readTree(r.getResponse().getContentAsString());
    }

    @Test
    void loginAndTokensAreChecked() throws Exception {
        mvc.perform(get("/api/v1/employees/A1")).andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
        for (String bad : new String[]{"{\"username\":\"admin-a-" + s + "\",\"password\":\"wrong\"}",
                "{\"username\":\"nobody\",\"password\":\"pw\"}"}) {   // same answer for both: no user enumeration
            mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON).content(bad))
                    .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"));
        }
        mvc.perform(get("/api/v1/employees/A1").header("Authorization",
                        "Bearer eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiJ4IiwidGlkIjoiYSIsInJvbGUiOiJBRE1JTiJ9.forged"))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/auth/me").header("Authorization", token("admin-a")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.role").value("ADMIN"))
                .andExpect(jsonPath("$.tenantId").value(a));
    }

    @Test
    void tenantsAreIsolated() throws Exception {
        mvc.perform(get("/api/v1/employees/A1").header("Authorization", token("admin-a"))).andExpect(status().isOk());
        // B1 exists, but in tenant B: answered exactly like "does not exist"
        mvc.perform(get("/api/v1/employees/B1").header("Authorization", token("admin-a"))).andExpect(status().isNotFound());

        send(b, upsert("CREATE", "A1", 1, "HYD1", "NIGHT"));   // same employee id in another tenant
        assertThat(json(mvc.perform(get("/api/v1/employees/A1").header("Authorization", token("admin-a"))).andReturn())
                .get("officeCode").asText()).isEqualTo("BLR1");
        assertThat(json(mvc.perform(get("/api/v1/employees/A1").header("Authorization", token("admin-b"))).andReturn())
                .get("officeCode").asText()).isEqualTo("HYD1");
    }

    @Test
    void rolesAreEnforced() throws Exception {
        mvc.perform(get("/api/v1/employees/me").header("Authorization", token("emp-a")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.employeeId").value("A1"));
        mvc.perform(get("/api/v1/employees/A2").header("Authorization", token("emp-a"))).andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/hr/events").header("Authorization", token("admin-a")).contentType(MediaType.APPLICATION_JSON)
                .content("{\"events\":[]}")).andExpect(status().isForbidden());
        mvc.perform(patch("/api/v1/employees/A1/transport").header("Authorization", token("hr-a"))
                .contentType(MediaType.APPLICATION_JSON).content("{\"pickupPoint\":\"x\"}")).andExpect(status().isForbidden());
        mvc.perform(get("/actuator/metrics").header("Authorization", token("admin-a"))).andExpect(status().isForbidden());
        mvc.perform(get("/actuator/metrics").header("Authorization", token("ops-a"))).andExpect(status().isOk());
        mvc.perform(patch("/api/v1/employees/A1/transport").header("Authorization", token("admin-a"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"pickupPoint\":\"Gate 2\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.pickupPoint").value("Gate 2"));
    }

    @Test
    void operatorOfOneTenantCannotApproveAnotherTenantsRun() throws Exception {
        MockMultipartFile headerOnly = new MockMultipartFile("file", "x.csv", "text/csv",
                "employee_id,version,name,email,phone,manager_id,office_code,shift_code\n".getBytes());
        JsonNode run = json(mvc.perform(multipart("/api/v1/hr/snapshots").file(headerOnly).header("Authorization", token("hr-b")))
                .andExpect(status().isCreated()).andReturn());
        assertThat(run.get("status").asText()).isEqualTo("AWAITING_APPROVAL");   // 100% missing -> brake

        String path = "/api/v1/ops/reconciliations/" + run.get("runId").asLong() + "/approve";
        mvc.perform(post(path).header("Authorization", token("ops-a"))).andExpect(status().isNotFound());
        mvc.perform(post(path).header("Authorization", token("ops-b"))).andExpect(status().isOk());
    }

    @Test
    void errorsAndReportsNeverEchoPersonalData() throws Exception {
        MvcResult malformed = mvc.perform(post("/api/v1/hr/events").header("Authorization", token("hr-a"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"events\": [ {\"phone\": \"+91SECRET\" "))
                .andExpect(status().isBadRequest()).andReturn();
        assertThat(malformed.getResponse().getContentAsString()).contains("MALFORMED_REQUEST").doesNotContain("SECRET");

        String body = """
                {"events":[
                  {"eventType":"CREATE","employeeId":"A9","version":1,"name":"Ok Person","email":"ok@acme.example",
                   "phone":"+919876543210","officeCode":"BLR1","shiftCode":"DAY"},
                  {"eventType":"CREATE","employeeId":"A10","version":1,"name":"Hidden Name","email":"hidden@acme.example",
                   "phone":"99-HIDDEN","officeCode":"BLR1","shiftCode":"DAY"}]}""";
        MvcResult r = mvc.perform(post("/api/v1/hr/events").header("Authorization", token("hr-a"))
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk()).andExpect(jsonPath("$.applied").value(1))
                .andExpect(jsonPath("$.errors[0].employeeId").value("A10")).andReturn();
        assertThat(r.getResponse().getContentAsString()).doesNotContainIgnoringCase("hidden");
    }
}

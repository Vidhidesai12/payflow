package com.payflow.fraud;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class FraudControllerTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    Chaos chaos;

    @AfterEach
    void calmDown() {
        chaos.set(new Chaos.Settings(0, 0));
    }

    @Test
    void ordinaryPaymentIsAllowed() throws Exception {
        check("ACC10001", "ACC20002").andExpect(status().isOk()).andExpect(jsonPath("$.decision").value("ALLOW"));
    }

    @Test
    void blockedAccountIsDenied() throws Exception {
        check("ACC10001", "BLOCKED0001").andExpect(jsonPath("$.decision").value("DENY"));
    }

    @Test
    void watchlistAccountNeedsReview() throws Exception {
        check("WATCH0001", "ACC20002").andExpect(jsonPath("$.decision").value("REVIEW"));
    }

    @Test
    void invalidRequestIsRejected() throws Exception {
        mvc.perform(post("/api/fraud-checks").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void chaosModeMakesChecksFail() throws Exception {
        mvc.perform(put("/api/chaos").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"failureRate\": 1.0, \"delayMs\": 0}"))
                .andExpect(status().isOk());

        check("ACC10001", "ACC20002").andExpect(status().is5xxServerError());
    }

    private org.springframework.test.web.servlet.ResultActions check(String debtor, String creditor) throws Exception {
        String body = """
                {"paymentId":"6f1c1b8e-6a43-4c55-9f0e-2a0b8f6f9a11","debtorAccount":"%s",
                 "creditorAccount":"%s","amount":100.00,"currency":"USD"}""".formatted(debtor, creditor);
        return mvc.perform(post("/api/fraud-checks").contentType(MediaType.APPLICATION_JSON).content(body));
    }
}

package com.oncology.intake;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;

/**
 * Cancer Patient Intake System - Main Application
 * 
 * WhatsApp-based intake system for cancer patients that:
 * - Captures patient details through conversational flow
 * - Stores medical reports (PET scan, blood reports)
 * - Generates initial medicine suggestions based on preset formulas
 * 
 * IMPORTANT: This system provides initial suggestions only.
 * All recommendations must be reviewed by a qualified oncologist.
 */
@SpringBootApplication
@EnableAsync
public class CancerIntakeApplication {

    public static void main(String[] args) {
        SpringApplication.run(CancerIntakeApplication.class, args);
    }
}

package com.oncology.intake.config;

import jakarta.annotation.PostConstruct;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import org.yaml.snakeyaml.Yaml;

import java.io.InputStream;
import java.util.List;
import java.util.Map;

/**
 * Configuration class for CancerQR Precision Oncology protocols.
 * Loads from cancerqr-protocols.yml at startup.
 */
@Component
@Slf4j
public class CancerQRProtocolConfig {

    private String version;
    private String lastUpdated;
    private String status;
    private String disclaimer;
    private MasterLists masterLists;
    private List<String> physicianDomains;
    private Map<String, CancerProtocol> cancerProtocols;

    @PostConstruct
    @SuppressWarnings("unchecked")
    public void init() {
        try {
            log.info("Loading CancerQR protocols from cancerqr-protocols.yml...");
            
            Yaml yaml = new Yaml();
            InputStream inputStream = new ClassPathResource("cancerqr-protocols.yml").getInputStream();
            Map<String, Object> data = yaml.load(inputStream);
            
            this.version = (String) data.get("version");
            this.lastUpdated = (String) data.get("last_updated");
            this.status = (String) data.get("status");
            this.disclaimer = (String) data.get("disclaimer");
            this.physicianDomains = (List<String>) data.get("physician_domains");
            
            // Parse master lists
            Map<String, Object> masterListsData = (Map<String, Object>) data.get("master_lists");
            if (masterListsData != null) {
                this.masterLists = new MasterLists();
                this.masterLists.setEcsProducts((List<String>) masterListsData.get("ecs_products"));
                this.masterLists.setMushrooms((List<String>) masterListsData.get("mushrooms"));
                this.masterLists.setAntiCancerHerbs((List<String>) masterListsData.get("anti_cancer_herbs"));
                this.masterLists.setRepurposedDrugs((List<String>) masterListsData.get("repurposed_drugs"));
                this.masterLists.setDietProtocols((List<String>) masterListsData.get("diet_protocols"));
                this.masterLists.setFastingProtocols((List<String>) masterListsData.get("fasting_protocols"));
            }
            
            // Parse cancer protocols
            Map<String, Object> protocolsData = (Map<String, Object>) data.get("cancer_protocols");
            if (protocolsData != null) {
                this.cancerProtocols = new java.util.HashMap<>();
                
                for (Map.Entry<String, Object> entry : protocolsData.entrySet()) {
                    String cancerId = entry.getKey();
                    Map<String, Object> cancerData = (Map<String, Object>) entry.getValue();
                    
                    CancerProtocol cancerProtocol = new CancerProtocol();
                    cancerProtocol.setName((String) cancerData.get("name"));
                    cancerProtocol.setId((String) cancerData.get("id"));
                    
                    // Parse physicians
                    Map<String, Object> physiciansData = (Map<String, Object>) cancerData.get("physicians");
                    if (physiciansData != null) {
                        Map<String, PhysicianProtocol> physicians = new java.util.HashMap<>();
                        
                        for (Map.Entry<String, Object> physEntry : physiciansData.entrySet()) {
                            String physId = physEntry.getKey();
                            Map<String, Object> physData = (Map<String, Object>) physEntry.getValue();
                            
                            PhysicianProtocol physicianProtocol = new PhysicianProtocol();
                            physicianProtocol.setName((String) physData.get("name"));
                            physicianProtocol.setId((String) physData.get("id"));
                            
                            // Parse protocols
                            Map<String, Object> protData = (Map<String, Object>) physData.get("protocols");
                            if (protData != null) {
                                Protocols protocols = new Protocols();
                                protocols.setEcsDefault((List<String>) protData.get("ecs_default"));
                                protocols.setEcsOptional((List<String>) protData.get("ecs_optional"));
                                protocols.setDiet((String) protData.get("diet"));
                                protocols.setFasting((String) protData.get("fasting"));
                                protocols.setLifestyle((String) protData.get("lifestyle"));
                                protocols.setMushrooms((List<String>) protData.get("mushrooms"));
                                protocols.setHerbs((List<String>) protData.get("herbs"));
                                protocols.setRepurposedDrugs((List<String>) protData.get("repurposed_drugs"));
                                protocols.setSpecialty((List<String>) protData.get("specialty"));
                                physicianProtocol.setProtocols(protocols);
                            }
                            
                            physicians.put(physId, physicianProtocol);
                        }
                        cancerProtocol.setPhysicians(physicians);
                    }
                    
                    this.cancerProtocols.put(cancerId, cancerProtocol);
                }
            }
            
            log.info("Loaded {} cancer protocols with {} master list categories", 
                    cancerProtocols != null ? cancerProtocols.size() : 0,
                    masterLists != null ? 6 : 0);
            
        } catch (Exception e) {
            log.error("Failed to load CancerQR protocols: {}", e.getMessage(), e);
            // Initialize empty to avoid NPE
            this.masterLists = new MasterLists();
            this.cancerProtocols = new java.util.HashMap<>();
        }
    }

    // Getters
    public String getVersion() { return version; }
    public String getLastUpdated() { return lastUpdated; }
    public String getStatus() { return status; }
    public String getDisclaimer() { return disclaimer; }
    public MasterLists getMasterLists() { return masterLists; }
    public List<String> getPhysicianDomains() { return physicianDomains; }
    public Map<String, CancerProtocol> getCancerProtocols() { return cancerProtocols; }

    @Data
    public static class MasterLists {
        private List<String> ecsProducts;
        private List<String> mushrooms;
        private List<String> antiCancerHerbs;
        private List<String> repurposedDrugs;
        private List<String> dietProtocols;
        private List<String> fastingProtocols;
    }

    @Data
    public static class CancerProtocol {
        private String name;
        private String id;
        private Map<String, PhysicianProtocol> physicians;
    }

    @Data
    public static class PhysicianProtocol {
        private String name;
        private String id;
        private Protocols protocols;
    }

    @Data
    public static class Protocols {
        private List<String> ecsDefault;
        private List<String> ecsOptional;
        private String diet;
        private String fasting;
        private String lifestyle;
        private List<String> mushrooms;
        private List<String> herbs;
        private List<String> repurposedDrugs;
        private List<String> specialty;
    }
}

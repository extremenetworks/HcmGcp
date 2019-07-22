package com.extremenetworks.hcm.gcp.mgr;

public class AccountConfig {

    // Extreme Networks tenant / account IDs
    private String tenantId;
    private String accountId;

    // Customer's Google account
    private String projectId;
    private String credentialsFileContent;

    public AccountConfig() {
    }

    public String getTenantId() {
        return tenantId;
    }

    public void setTenantId(String tenantId) {
        this.tenantId = tenantId;
    }

    public String getAccountId() {
        return accountId;
    }

    public void setAccountId(String accountId) {
        this.accountId = accountId;
    }

    public String getProjectId() {
        return projectId;
    }

    public void setProjectId(String projectId) {
        this.projectId = projectId;
    }

    public String getCredentialsFileContent() {
        return credentialsFileContent;
    }

    public void setCredentialsFileContent(String credentialsFileContent) {
        this.credentialsFileContent = credentialsFileContent;
    }

}
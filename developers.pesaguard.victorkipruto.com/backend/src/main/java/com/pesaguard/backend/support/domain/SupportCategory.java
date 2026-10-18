package com.pesaguard.backend.support.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "support_categories")
public class SupportCategory {

    @Id
    private String id;

    @Column(name = "name", nullable = false, unique = true, length = 80)
    private String name;

    @Column(name = "sort_order", nullable = false, unique = true)
    private int sortOrder;

    protected SupportCategory() {
    }

    public String getId() { return id; }
    public String getName() { return name; }
    public int getSortOrder() { return sortOrder; }
}

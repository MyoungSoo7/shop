package github.lms.lemuel.rbac.domain;

/**
 * Permission 도메인 모델 (순수 POJO)
 */
public class Permission {

    private Long id;
    private String code;
    private String name;
    private String category;
    private String description;

    public Permission() {}

    public static Permission of(Long id, String code, String name,
                                String category, String description) {
        Permission p = new Permission();
        p.id = id;
        p.code = code;
        p.name = name;
        p.category = category;
        p.description = description;
        return p;
    }

    /** DB 부여 PK 주입(setter 대체). */
    public void assignId(Long id) { this.id = id; }

    // Getters
    public Long getId() { return id; }

    public String getCode() { return code; }

    public String getName() { return name; }

    public String getCategory() { return category; }

    public String getDescription() { return description; }
}

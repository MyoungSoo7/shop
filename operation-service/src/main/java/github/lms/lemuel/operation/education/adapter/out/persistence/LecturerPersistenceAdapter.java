package github.lms.lemuel.operation.education.adapter.out.persistence;

import github.lms.lemuel.operation.education.application.port.dto.PageSlice;
import github.lms.lemuel.operation.education.application.port.dto.PageSpec;
import github.lms.lemuel.operation.education.application.port.out.LoadLecturerPort;
import github.lms.lemuel.operation.education.application.port.out.SaveLecturerPort;
import github.lms.lemuel.operation.education.domain.Lecturer;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.UUID;

/** 강사 영속 어댑터 — Spring Data 타입은 이 경계 안에서만 쓴다. */
@Component
public class LecturerPersistenceAdapter implements LoadLecturerPort, SaveLecturerPort {

    private final LecturerRepository lecturers;

    public LecturerPersistenceAdapter(LecturerRepository lecturers) { this.lecturers = lecturers; }

    @Override
    public Optional<Lecturer> findById(UUID id) {
        return lecturers.findById(id).map(LecturerJpaEntity::toDomain);
    }

    @Override
    public PageSlice<Lecturer> search(String keyword, boolean activeOnly, PageSpec page) {
        // 이름 오름차순이 기본이다 — 정렬이 없으면 페이지마다 순서가 달라져 같은 강사가 두 번
        // 보이거나 아예 빠진다(수강 신청 목록과 같은 이유).
        Pageable pageable = PageRequest.of(page.page(), page.size(), Sort.by(Sort.Direction.ASC, "name"));
        Page<LecturerJpaEntity> found = lecturers.search(keyword, activeOnly, pageable);
        return new PageSlice<>(found.getContent().stream().map(LecturerJpaEntity::toDomain).toList(),
                page.page(), page.size(), found.getTotalElements());
    }

    @Override
    public Lecturer save(Lecturer lecturer) {
        LecturerJpaEntity entity = lecturers.findById(lecturer.id()).orElse(null);
        if (entity == null) {
            entity = LecturerJpaEntity.fromDomain(lecturer);
        } else {
            entity.sync(lecturer);
        }
        return lecturers.save(entity).toDomain();
    }
}

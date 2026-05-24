package org.modmed.department.service.impl;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.modmed.department.dto.DepartmentDto;
import org.modmed.department.entity.Department;
import org.modmed.department.exception.DepartmentNotFoundException;
import org.modmed.department.repository.DepartmentRepository;
import org.modmed.department.service.DepartmentService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class DepartmentServiceImpl implements DepartmentService {

    private final DepartmentRepository departmentRepository;

    @Override
    @Transactional
    public DepartmentDto createDepartment(DepartmentDto departmentDto) {
        log.info("Creating department: {}", departmentDto.getDepartmentName());
        Department department = Department.builder()
                .departmentName(departmentDto.getDepartmentName())
                .departmentCode(departmentDto.getDepartmentCode())
                .build();
        Department saved = departmentRepository.save(department);
        log.info("Department created with id: {}", saved.getId());
        return toDto(saved);
    }

    @Override
    @Transactional(readOnly = true)
    public DepartmentDto getDepartmentById(Long id) {
        log.info("Fetching department with id: {}", id);
        Department department = departmentRepository.findById(id)
                .orElseThrow(() -> new DepartmentNotFoundException("Department not found with id: " + id));
        return toDto(department);
    }

    @Override
    @Transactional(readOnly = true)
    public List<DepartmentDto> getAllDepartments() {
        log.info("Fetching all departments");
        return departmentRepository.findAll().stream()
                .map(this::toDto)
                .toList();
    }

    @Override
    @Transactional
    public DepartmentDto updateDepartment(Long id, DepartmentDto departmentDto) {
        log.info("Updating department with id: {}", id);
        Department existing = departmentRepository.findById(id)
                .orElseThrow(() -> new DepartmentNotFoundException("Department not found with id: " + id));
        existing.setDepartmentName(departmentDto.getDepartmentName());
        existing.setDepartmentCode(departmentDto.getDepartmentCode());
        Department updated = departmentRepository.save(existing);
        log.info("Department updated with id: {}", updated.getId());
        return toDto(updated);
    }

    @Override
    @Transactional
    public void deleteDepartment(Long id) {
        log.info("Deleting department with id: {}", id);
        if (!departmentRepository.existsById(id)) {
            throw new DepartmentNotFoundException("Department not found with id: " + id);
        }
        departmentRepository.deleteById(id);
        log.info("Department deleted with id: {}", id);
    }

    private DepartmentDto toDto(Department department) {
        return DepartmentDto.builder()
                .id(department.getId())
                .departmentName(department.getDepartmentName())
                .departmentCode(department.getDepartmentCode())
                .build();
    }
}

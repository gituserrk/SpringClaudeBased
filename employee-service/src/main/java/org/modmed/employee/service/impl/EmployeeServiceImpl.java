package org.modmed.employee.service.impl;

import lombok.extern.slf4j.Slf4j;
import org.modmed.employee.client.DepartmentClient;
import org.modmed.employee.dto.ApiResponseDto;
import org.modmed.employee.dto.DepartmentDto;
import org.modmed.employee.dto.EmployeeDto;
import org.modmed.employee.entity.Employee;
import org.modmed.employee.exception.EmployeeNotFoundException;
import org.modmed.employee.repository.EmployeeRepository;
import org.modmed.employee.service.EmployeeService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Slf4j
@Service
public class EmployeeServiceImpl implements EmployeeService {

    private final EmployeeRepository employeeRepository;
    private final DepartmentClient departmentClient;

    public EmployeeServiceImpl(EmployeeRepository employeeRepository,
                               DepartmentClient departmentClient) {
        this.employeeRepository = employeeRepository;
        this.departmentClient = departmentClient;
    }

    @Override
    @Transactional
    public EmployeeDto createEmployee(EmployeeDto employeeDto) {
        log.info("Creating employee with email: {}", employeeDto.getEmail());
        Employee employee = Employee.builder()
                .firstName(employeeDto.getFirstName())
                .lastName(employeeDto.getLastName())
                .email(employeeDto.getEmail())
                .departmentId(employeeDto.getDepartmentId())
                .build();
        Employee saved = employeeRepository.save(employee);
        log.info("Employee created with id: {}", saved.getId());
        return toDto(saved);
    }

    @Override
    @Transactional(readOnly = true)
    public ApiResponseDto getEmployeeById(Long id) {
        log.info("Fetching employee with id: {}", id);
        Employee employee = employeeRepository.findById(id)
                .orElseThrow(() -> new EmployeeNotFoundException("Employee not found with id: " + id));

        // DepartmentClient handles all resilience concerns (CB, Retry, RL, BH).
        // It always returns a DepartmentDto — either real or a degraded placeholder.
        DepartmentDto department = departmentClient.fetchDepartment(employee.getDepartmentId());

        return ApiResponseDto.builder()
                .employee(toDto(employee))
                .department(department)
                .build();
    }

    @Override
    @Transactional(readOnly = true)
    public List<EmployeeDto> getAllEmployees() {
        log.info("Fetching all employees");
        return employeeRepository.findAll().stream()
                .map(this::toDto)
                .toList();
    }

    private EmployeeDto toDto(Employee employee) {
        return EmployeeDto.builder()
                .id(employee.getId())
                .firstName(employee.getFirstName())
                .lastName(employee.getLastName())
                .email(employee.getEmail())
                .departmentId(employee.getDepartmentId())
                .build();
    }
}

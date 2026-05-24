package org.modmed.employee.service;

import org.modmed.employee.dto.ApiResponseDto;
import org.modmed.employee.dto.EmployeeDto;

import java.util.List;

public interface EmployeeService {
    EmployeeDto createEmployee(EmployeeDto employeeDto);
    ApiResponseDto getEmployeeById(Long id);
    List<EmployeeDto> getAllEmployees();
}

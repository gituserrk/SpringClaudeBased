package org.modmed.employee.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.modmed.employee.client.DepartmentClient;
import org.modmed.employee.dto.ApiResponseDto;
import org.modmed.employee.dto.DepartmentDto;
import org.modmed.employee.dto.EmployeeDto;
import org.modmed.employee.entity.Employee;
import org.modmed.employee.exception.EmployeeNotFoundException;
import org.modmed.employee.repository.EmployeeRepository;
import org.modmed.employee.service.impl.EmployeeServiceImpl;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for EmployeeServiceImpl.
 *
 * <h3>Strategy</h3>
 * <ul>
 *   <li>Uses Mockito extension — NO Spring context, fast execution.</li>
 *   <li>Verifies the service delegates resilience concerns to DepartmentClient.</li>
 *   <li>Validates the aggregation logic (Employee + Department → ApiResponseDto).</li>
 *   <li>Confirms graceful degradation: if DepartmentClient returns a placeholder,
 *       the service still assembles a valid (partial) ApiResponseDto.</li>
 * </ul>
 *
 * <p>Full Resilience4j AOP behaviour (CB open, retries, rate limiting) is
 * tested in {@code DepartmentClientTest} where the Spring context is active.</p>
 */
@ExtendWith(MockitoExtension.class)
class EmployeeServiceImplTest {

    @Mock
    private EmployeeRepository employeeRepository;

    @Mock
    private DepartmentClient departmentClient;

    @InjectMocks
    private EmployeeServiceImpl employeeService;

    // ── Fixtures ──────────────────────────────────────────────────────────────

    private Employee sampleEmployee;
    private DepartmentDto sampleDepartment;
    private DepartmentDto degradedDepartment;

    @BeforeEach
    void setUp() {
        sampleEmployee = Employee.builder()
                .id(1L)
                .firstName("Alice")
                .lastName("Smith")
                .email("alice@modmed.com")
                .departmentId(10L)
                .build();

        sampleDepartment = DepartmentDto.builder()
                .id(10L)
                .departmentName("Engineering")
                .departmentCode("ENG")
                .build();

        degradedDepartment = DepartmentDto.builder()
                .id(10L)
                .departmentName("Department information temporarily unavailable")
                .departmentCode("N/A")
                .build();
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  createEmployee
    // ─────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("createEmployee()")
    class CreateEmployee {

        @Test
        @DisplayName("Should save and return DTO when input is valid")
        void shouldCreateEmployee() {
            EmployeeDto input = EmployeeDto.builder()
                    .firstName("Alice").lastName("Smith")
                    .email("alice@modmed.com").departmentId(10L)
                    .build();

            when(employeeRepository.save(any(Employee.class))).thenReturn(sampleEmployee);

            EmployeeDto result = employeeService.createEmployee(input);

            assertThat(result.getId()).isEqualTo(1L);
            assertThat(result.getEmail()).isEqualTo("alice@modmed.com");
            verify(employeeRepository).save(any(Employee.class));
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  getEmployeeById — happy path
    // ─────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("getEmployeeById()")
    class GetEmployeeById {

        @Test
        @DisplayName("Should return enriched response when both employee and department exist")
        void shouldReturnEnrichedResponseOnSuccess() {
            when(employeeRepository.findById(1L)).thenReturn(Optional.of(sampleEmployee));
            when(departmentClient.fetchDepartment(10L)).thenReturn(sampleDepartment);

            ApiResponseDto response = employeeService.getEmployeeById(1L);

            assertThat(response.getEmployee().getEmail()).isEqualTo("alice@modmed.com");
            assertThat(response.getDepartment().getDepartmentCode()).isEqualTo("ENG");
            verify(departmentClient).fetchDepartment(eq(10L));
        }

        @Test
        @DisplayName("Should throw EmployeeNotFoundException when employee does not exist")
        void shouldThrowWhenEmployeeNotFound() {
            when(employeeRepository.findById(99L)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> employeeService.getEmployeeById(99L))
                    .isInstanceOf(EmployeeNotFoundException.class)
                    .hasMessageContaining("99");

            // Department client must NOT be called if employee doesn't exist
            verify(departmentClient, never()).fetchDepartment(any());
        }

        @Test
        @DisplayName("Should return degraded ApiResponseDto when department-service is unavailable")
        void shouldReturnDegradedResponseWhenDepartmentServiceIsDown() {
            // DepartmentClient handles CB/Retry internally and returns placeholder
            when(employeeRepository.findById(1L)).thenReturn(Optional.of(sampleEmployee));
            when(departmentClient.fetchDepartment(10L)).thenReturn(degradedDepartment);

            ApiResponseDto response = employeeService.getEmployeeById(1L);

            // Employee data is complete
            assertThat(response.getEmployee().getEmail()).isEqualTo("alice@modmed.com");

            // Department is degraded but NOT null — API still returns 200 OK
            assertThat(response.getDepartment()).isNotNull();
            assertThat(response.getDepartment().getDepartmentCode()).isEqualTo("N/A");
            assertThat(response.getDepartment().getDepartmentName())
                    .contains("temporarily unavailable");
        }

        @Test
        @DisplayName("Should preserve correct departmentId mapping in ApiResponseDto")
        void shouldPreserveDepartmentIdMapping() {
            Employee empWithDept5 = Employee.builder()
                    .id(2L).firstName("Bob").lastName("Jones")
                    .email("bob@modmed.com").departmentId(5L).build();

            DepartmentDto dept5 = DepartmentDto.builder()
                    .id(5L).departmentName("Finance").departmentCode("FIN").build();

            when(employeeRepository.findById(2L)).thenReturn(Optional.of(empWithDept5));
            when(departmentClient.fetchDepartment(5L)).thenReturn(dept5);

            ApiResponseDto response = employeeService.getEmployeeById(2L);

            assertThat(response.getEmployee().getDepartmentId()).isEqualTo(5L);
            assertThat(response.getDepartment().getId()).isEqualTo(5L);
            assertThat(response.getDepartment().getDepartmentCode()).isEqualTo("FIN");
            // Verify the right departmentId was passed to the client
            verify(departmentClient).fetchDepartment(eq(5L));
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  getAllEmployees
    // ─────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("getAllEmployees()")
    class GetAllEmployees {

        @Test
        @DisplayName("Should return empty list when no employees exist")
        void shouldReturnEmptyList() {
            when(employeeRepository.findAll()).thenReturn(List.of());

            List<EmployeeDto> result = employeeService.getAllEmployees();

            assertThat(result).isEmpty();
        }

        @Test
        @DisplayName("Should return all employees as DTOs")
        void shouldReturnAllEmployees() {
            Employee emp2 = Employee.builder()
                    .id(2L).firstName("Bob").lastName("Jones")
                    .email("bob@modmed.com").departmentId(20L).build();

            when(employeeRepository.findAll()).thenReturn(List.of(sampleEmployee, emp2));

            List<EmployeeDto> result = employeeService.getAllEmployees();

            assertThat(result).hasSize(2);
            assertThat(result).extracting(EmployeeDto::getEmail)
                    .containsExactly("alice@modmed.com", "bob@modmed.com");
        }
    }
}

package io.kneo.projects.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.kneo.core.dto.AbstractDTO;
import io.kneo.core.dto.rls.RLSDTO;
import io.kneo.core.model.constants.ProjectStatusType;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.SuperBuilder;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@JsonInclude(JsonInclude.Include.NON_NULL)
@Setter
@Getter
@SuperBuilder
@NoArgsConstructor
public class ProjectDTO extends AbstractDTO {
    private String name;
    private ProjectStatusType status;
    private LocalDate finishDate;
    private String manager;
    private String coder;
    private String tester;
    private List<RLSDTO> rls = new ArrayList<>();

    public ProjectDTO(String id) {
        this.id = UUID.fromString(id);
    }

}
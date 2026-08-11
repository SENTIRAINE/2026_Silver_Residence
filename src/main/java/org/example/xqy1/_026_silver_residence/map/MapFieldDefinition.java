package org.example.xqy1._026_silver_residence.map;

import java.util.List;

public record MapFieldDefinition(
        String name,
        String label,
        String type,
        List<String> operators
) {
}

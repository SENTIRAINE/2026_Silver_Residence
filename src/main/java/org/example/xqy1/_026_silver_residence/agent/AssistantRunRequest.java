package org.example.xqy1._026_silver_residence.agent;

import java.util.List;
import java.util.UUID;

public record AssistantRunRequest(
        UUID conversationId,
        UUID messageId,
        String query,
        Context context
) {
    public AssistantRunRequest {
        context = context == null ? Context.empty() : context;
    }

    public record Context(
            String locale,
            MapContext map,
            List<String> businessObjectIds
    ) {
        public Context {
            locale = locale == null || locale.isBlank() ? "zh-CN" : locale;
            businessObjectIds = businessObjectIds == null ? List.of() : List.copyOf(businessObjectIds);
        }

        public static Context empty() {
            return new Context("zh-CN", null, List.of());
        }
    }

    public record MapContext(
            List<Integer> visibleLayerIds,
            Double zoom,
            Extent extent
    ) {
        public MapContext {
            visibleLayerIds = visibleLayerIds == null ? List.of() : List.copyOf(visibleLayerIds);
        }
    }

    public record Extent(
            Double xmin,
            Double ymin,
            Double xmax,
            Double ymax,
            Integer wkid
    ) {
        public Extent {
            wkid = wkid == null ? 4326 : wkid;
        }
    }
}

package top.outlands.jst.mcp.export;

import java.lang.constant.MethodTypeDesc;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

final class ParameterOrdinals {
    private static final Pattern METHOD = Pattern.compile("func_(\\d+)_[a-zA-Z_]+");
    private static final Pattern PARAM = Pattern.compile("p_i?(\\d+)_(\\d+)_");

    record Parameter(int slot, String key, String name) {}

    private final Map<String, List<Parameter>> byId = new HashMap<>();

    ParameterOrdinals(Map<String, String> names) {
        names.forEach((key, name) -> {
            var match = PARAM.matcher(key);
            if (match.matches()) byId.computeIfAbsent(match.group(1), unused -> new ArrayList<>())
                    .add(new Parameter(Integer.parseInt(match.group(2)), key, name));
        });
        byId.values().forEach(list -> list.sort(Comparator.comparingInt(Parameter::slot)));
    }

    List<Parameter> forMethod(String name, String descriptor, boolean knownStatic) {
        var match = METHOD.matcher(name);
        if (!match.matches()) return List.of();
        var params = byId.get(match.group(1));
        if (params == null) return List.of();
        var types = MethodTypeDesc.ofDescriptor(descriptor).parameterList();
        if (params.size() != types.size()) return List.of();
        int slot = params.getFirst().slot;
        if (slot != 0 && (slot != 1 || knownStatic)) return List.of();
        for (int i = 0; i < types.size(); i++) {
            if (params.get(i).slot != slot) return List.of();
            slot += switch (types.get(i).descriptorString()) {
                case "J", "D" -> 2;
                default -> 1;
            };
        }
        return params;
    }
}

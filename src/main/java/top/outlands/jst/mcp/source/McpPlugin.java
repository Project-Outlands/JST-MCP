package top.outlands.jst.mcp.source;

import net.neoforged.jst.api.SourceTransformer;
import net.neoforged.jst.api.SourceTransformerPlugin;

public final class McpPlugin implements SourceTransformerPlugin {
    @Override
    public String getName() {
        return "mcp";
    }

    @Override
    public SourceTransformer createTransformer() {
        return new McpTransformer();
    }
}

package com.kordonlabs.plugin.common.json;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import com.google.gson.TypeAdapter;
import com.google.gson.TypeAdapterFactory;
import com.google.gson.reflect.TypeToken;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonWriter;
import com.kordonlabs.plugin.common.api.dto.SyncEvent;
import java.io.IOException;
import java.util.Map;

/**
 * Reads and writes {@link SyncEvent} with its {@code "t"} discriminator: {@code {"t":"join","sid":...}}. Applies only
 * to the declared type {@code SyncEvent}; the concrete records use the normal reflective adapter. An unknown or missing
 * {@code "t"} reads as {@code null}.
 */
final class SyncEventAdapterFactory implements TypeAdapterFactory {

    @Override
    @SuppressWarnings("unchecked")
    public <T> TypeAdapter<T> create(Gson gson, TypeToken<T> type) {
        if (type.getRawType() != SyncEvent.class) return null;
        TypeAdapter<JsonElement> elements = gson.getAdapter(JsonElement.class);
        TypeAdapterFactory self = this;
        return (TypeAdapter<T>) new TypeAdapter<SyncEvent>() {
            @Override
            public void write(JsonWriter out, SyncEvent event) throws IOException {
                if (event == null) {
                    out.nullValue();
                    return;
                }
                JsonObject object = new JsonObject();
                object.addProperty("t", event.type());
                JsonElement fields = delegate(event.getClass()).toJsonTree(event);
                for (Map.Entry<String, JsonElement> field : fields.getAsJsonObject().entrySet()) {
                    object.add(field.getKey(), field.getValue()); // JsonNull values are dropped by the writer
                }
                elements.write(out, object);
            }

            @Override
            public SyncEvent read(JsonReader in) throws IOException {
                JsonElement element = elements.read(in);
                if (element == null || !element.isJsonObject()) return null;
                JsonElement t = element.getAsJsonObject().get("t");
                if (!(t instanceof JsonPrimitive p) || !p.isString()) return null;
                Class<? extends SyncEvent> concrete = switch (p.getAsString()) {
                    case SyncEvent.JOIN -> SyncEvent.Join.class;
                    case SyncEvent.INFO -> SyncEvent.Info.class;
                    case SyncEvent.QUIT -> SyncEvent.Quit.class;
                    default -> null;
                };
                return concrete == null ? null : delegate(concrete).fromJsonTree(element);
            }

            @SuppressWarnings("unchecked")
            private <E extends SyncEvent> TypeAdapter<E> delegate(Class<? extends SyncEvent> concrete) {
                return (TypeAdapter<E>) gson.getDelegateAdapter(self, TypeToken.get(concrete));
            }
        }.nullSafe();
    }
}

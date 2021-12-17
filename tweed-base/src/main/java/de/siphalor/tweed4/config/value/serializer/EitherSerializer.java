package de.siphalor.tweed4.config.value.serializer;

import com.mojang.datafixers.util.Either;
import de.siphalor.tweed4.config.ConfigReadException;
import de.siphalor.tweed4.data.DataContainer;
import de.siphalor.tweed4.data.DataList;
import de.siphalor.tweed4.data.DataObject;
import de.siphalor.tweed4.data.DataValue;
import net.minecraft.network.PacketByteBuf;

import java.util.Optional;

/**
 * A serializer for {@link Either}s
 * @since 1.3.2
 */
public class EitherSerializer<A, B> extends ConfigValueSerializer<Either<A, B>> {
	private final ConfigValueSerializer<A> leftSerializer;
	private final ConfigValueSerializer<B> rightSerializer;

	public EitherSerializer(ConfigValueSerializer<A> leftSerializer, ConfigValueSerializer<B> rightSerializer) {
		this.leftSerializer = leftSerializer;
		this.rightSerializer = rightSerializer;
	}

	@Override
	public <V extends DataValue<V, L, O>, L extends DataList<V, L, O>, O extends DataObject<V, L, O>>
	Either<A, B> read(V data) throws ConfigReadException {
		try {
			A leftValue = leftSerializer.read(data);
			return Either.left(leftValue);
		} catch (ConfigReadException leftException) {
			try {
				B rightValue = rightSerializer.read(data);
				return Either.right(rightValue);
			} catch (ConfigReadException rightException) {
				throw new ConfigReadException("Failed to deserialize either of two values:\n    " + leftException + "\nand " + rightException);
			}
		}
	}

	@Override
	public <Key, V extends DataValue<V, L, O>, L extends DataList<V, L, O>, O extends DataObject<V, L, O>>
	void write(DataContainer<Key, V, L, O> dataContainer, Key key, Either<A, B> value) {
		value.ifLeft(leftValue -> leftSerializer.write(dataContainer, key, leftValue));
		value.ifRight(rightValue -> rightSerializer.write(dataContainer, key, rightValue));
	}

	@Override
	public Either<A, B> read(PacketByteBuf packetByteBuf) {
		if (packetByteBuf.readBoolean()) {
			return Either.right(rightSerializer.read(packetByteBuf));
		} else {
			return Either.left(leftSerializer.read(packetByteBuf));
		}
	}

	@Override
	public void write(PacketByteBuf packetByteBuf, Either<A, B> value) {
		Optional<A> leftOpt = value.left();
		if (leftOpt.isPresent()) {
			packetByteBuf.writeBoolean(false);
			leftSerializer.write(packetByteBuf, leftOpt.get());
		} else {
			packetByteBuf.writeBoolean(true);
			//noinspection OptionalGetWithoutIsPresent
			rightSerializer.write(packetByteBuf, value.right().get());
		}
	}

	@Override
	public String asString(Either<A, B> value) {
		return value.map(leftSerializer::asString, rightSerializer::asString);
	}

	@Override
	public Class<Either<A, B>> getType() {
		//noinspection unchecked
		return ((Class<Either<A,B>>)(Object) Either.class);
	}
}

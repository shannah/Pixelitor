/*
 * Copyright 2024 Laszlo Balazs-Csiki and Contributors
 *
 * This file is part of Pixelitor. Pixelitor is free software: you
 * can redistribute it and/or modify it under the terms of the GNU
 * General Public License, version 3 as published by the Free
 * Software Foundation.
 *
 * Pixelitor is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with Pixelitor. If not, see <http://www.gnu.org/licenses/>.
 */

package pixelitor.utils;

import java.util.function.Supplier;

/**
 * A memoizing Supplier
 */
public class Lazy<T> implements Supplier<T> {
    private final Supplier<T> supplier;
    private volatile T cachedValue;

    private Lazy(Supplier<T> supplier) {
        this.supplier = supplier;
    }

    public static <T> Lazy<T> of(Supplier<T> supplier) {
        return new Lazy<>(supplier);
    }

    @Override
    public T get() {
        if (cachedValue != null) {
            return cachedValue;
        }
        cachedValue = supplier.get();
        if (cachedValue == null) {
            throw new IllegalStateException();
        }
        return cachedValue;
    }

    /**
     * Make sure that the value is re-calculated the next time
     */
    public void invalidate() {
        cachedValue = null;
    }
}

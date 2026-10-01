import * as api from '../../generated/typescript/cq/api/index.js';
import { BaboonCodecContext } from '../../generated/typescript/BaboonSharedRuntime.js';

const STORAGE_KEY = 'cq-items-view';

export class ItemsView {
  constructor(private readonly storage: Storage, private readonly warning: (message: string) => void) {}

  // An unreadable or outdated saved view is not an operator-visible fault: the default order applies and the next change replaces it.
  load(): api.ItemOrder {
    try {
      const raw = this.storage.getItem(STORAGE_KEY);
      if (raw !== null) {
        const order = api.ItemOrder_JsonCodec.instance.decode(BaboonCodecContext.Default, JSON.parse(raw));
        // The generated decoder validates the enumerations but casts the flag.
        if (typeof order.grouped === 'boolean') return order;
      }
    } catch { /* default below */ }
    return new api.ItemOrder(api.ItemOrderField.Id, api.SortDirection.Ascending, false);
  }

  store(order: api.ItemOrder): void {
    try { this.storage.setItem(STORAGE_KEY, JSON.stringify(api.ItemOrder_JsonCodec.instance.encode(BaboonCodecContext.Default, order))); }
    catch { this.warning('Items view could not be saved in this browser.'); }
  }
}

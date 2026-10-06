import * as api from '../../generated/typescript/cq/api/index.js';
import { faultMessage } from './faults.js';

/** The typed `ReadSelection.Catalog` response, read once per page for Help and the process mode controls; a failed read is retried on the next request. */
export class CatalogSource {
  private loading: Promise<api.HelpCatalog> | null = null;
  constructor(private readonly call: (command: api.Command) => Promise<api.Result>) {}
  read(project: api.ProjectId): Promise<api.HelpCatalog> {
    this.loading ??= this.fetch(project).catch(error => { this.loading = null; throw error; });
    return this.loading;
  }
  private async fetch(project: api.ProjectId): Promise<api.HelpCatalog> {
    const result = await this.call(new api.Command_Read(new api.ReadInput(project, new api.ReadSelection_Catalog())));
    if (result instanceof api.Result_Failed) throw new Error(faultMessage(result.fault));
    if (!(result instanceof api.Result_Catalog)) throw new Error('Unexpected help catalog response');
    return result.value;
  }
}

import { test as base, type Page } from '@playwright/test';
import { loginAsAdmin, mockAllApiRoutes } from './api-mocks';

type Fixtures = {
    authenticatedPage: Page;
};

export const test = base.extend<Fixtures>({
    authenticatedPage: async ({ page }, runFixture) => {
        await mockAllApiRoutes(page);
        await loginAsAdmin(page);
        await runFixture(page);
    },
});

export { expect } from '@playwright/test';

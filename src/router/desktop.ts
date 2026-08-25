import { type NavigationGuardReturn, type RouteLocationNormalized, createRouter, createWebHashHistory } from 'vue-router';

import { TemplateType } from '@/core/template.ts';
import { isUserLogined, isUserUnlocked } from '@/lib/userstate.ts';

import MainLayout from '@/views/desktop/MainLayout.vue';
import LoginPage from '@/views/desktop/LoginPage.vue';
import SignUpPage from '@/views/desktop/SignupPage.vue';
import VerifyEmailPage from '@/views/desktop/VerifyEmailPage.vue';
import ForgetPasswordPage from '@/views/desktop/ForgetPasswordPage.vue';
import ResetPasswordPage from '@/views/desktop/ResetPasswordPage.vue';
import OAuth2CallbackPage from '@/views/desktop/OAuth2CallbackPage.vue';
import UnlockPage from '@/views/desktop/UnlockPage.vue';

import HomePage from '@/views/desktop/HomePage.vue';
import FinexyWorkspacePage from '@/views/desktop/FinexyWorkspacePage.vue';

function checkLogin(): NavigationGuardReturn {
    if (!isUserLogined()) {
        return {
            path: '/login',
            replace: true
        };
    }

    if (!isUserUnlocked()) {
        return {
            path: '/unlock',
            replace: true
        };
    }

    return true;
}

function checkLocked(): NavigationGuardReturn {
    if (!isUserLogined()) {
        return {
            path: '/login',
            replace: true
        };
    }

    if (isUserUnlocked()) {
        return {
            path: '/',
            replace: true
        };
    }

    return true;
}

function checkNotLogin(to: RouteLocationNormalized): NavigationGuardReturn {
    if (to.path === '/login' && to.query['switch'] === '1') {
        return true;
    }

    if (isUserLogined() && !isUserUnlocked()) {
        return {
            path: '/unlock',
            replace: true
        };
    }

    if (isUserLogined()) {
        return {
            path: '/',
            replace: true
        };
    }

    return true;
}

const router = createRouter({
    history: createWebHashHistory(),
    routes: [
        {
            path: '/',
            component: MainLayout,
            beforeEnter: checkLogin,
            children: [
                {
                    path: '',
                    component: HomePage,
                    beforeEnter: checkLogin,
                    meta: { finexy: true }
                },
                {
                    path: '/transaction/list',
                    component: FinexyWorkspacePage,
                    beforeEnter: checkLogin,
                    meta: { finexy: true },
                    props: route => ({
                        initPageType: route.query['pageType'],
                        initDateType: route.query['dateType'],
                        initMaxTime: route.query['maxTime'],
                        initMinTime: route.query['minTime'],
                        initType: route.query['type'],
                        initCategoryIds: route.query['categoryIds'],
                        initAccountIds: route.query['accountIds'],
                        initTagFilter: route.query['tagFilter'],
                        initAmountFilter: route.query['amountFilter'],
                        initKeyword: route.query['keyword'],
                        initMatchMode: route.query['matchMode']
                    })
                },
                {
                    path: '/statistics/transaction',
                    component: FinexyWorkspacePage,
                    beforeEnter: checkLogin,
                    meta: { finexy: true },
                    props: route => ({
                        initAnalysisType: route.query['analysisType'],
                        initChartDataType: route.query['chartDataType'],
                        initChartType: route.query['chartType'],
                        initChartDateType: route.query['chartDateType'],
                        initStartTime: route.query['startTime'],
                        initEndTime: route.query['endTime'],
                        initFilterAccountIds: route.query['filterAccountIds'],
                        initFilterCategoryIds: route.query['filterCategoryIds'],
                        initTagFilter: route.query['tagFilter'],
                        initKeyword: route.query['keyword'],
                        initMatchMode: route.query['matchMode'],
                        initSortingType: route.query['sortingType'],
                        initTrendDateAggregationType: route.query['trendDateAggregationType'],
                        initAssetTrendsDateAggregationType: route.query['assetTrendsDateAggregationType']
                    })
                },
                {
                    path: '/insights/explorer',
                    component: FinexyWorkspacePage,
                    beforeEnter: checkLogin,
                    meta: { finexy: true },
                    props: route => ({
                        initId: route.query['id'],
                        initActiveTab: route.query['activeTab'],
                        initDateRangeType: route.query['dateRangeType'],
                        initStartTime: route.query['startTime'],
                        initEndTime: route.query['endTime']
                    })
                },
                {
                    path: '/account/list',
                    component: FinexyWorkspacePage,
                    beforeEnter: checkLogin,
                    meta: { finexy: true }
                },
                {
                    path: '/product/assets',
                    component: FinexyWorkspacePage,
                    beforeEnter: checkLogin,
                    meta: { finexy: true }
                },
                {
                    path: '/category/list',
                    component: FinexyWorkspacePage,
                    beforeEnter: checkLogin,
                    meta: { finexy: true }
                },
                {
                    path: '/tag/list',
                    component: FinexyWorkspacePage,
                    beforeEnter: checkLogin,
                    meta: { finexy: true }
                },
                {
                    path: '/template/list',
                    component: FinexyWorkspacePage,
                    beforeEnter: checkLogin,
                    meta: { finexy: true },
                    props: {
                        initType: TemplateType.Normal.type
                    }
                },
                {
                    path: '/schedule/list',
                    component: FinexyWorkspacePage,
                    beforeEnter: checkLogin,
                    meta: { finexy: true },
                    props: {
                        initType: TemplateType.Schedule.type
                    }
                },
                {
                    path: '/exchange_rates',
                    component: FinexyWorkspacePage,
                    beforeEnter: checkLogin,
                    meta: { finexy: true }
                },
                {
                    path: '/user/settings',
                    component: FinexyWorkspacePage,
                    beforeEnter: checkLogin,
                    meta: { finexy: true },
                    props: route => ({
                        initTab: route.query['tab']
                    })
                },
                {
                    path: '/app/settings',
                    component: FinexyWorkspacePage,
                    beforeEnter: checkLogin,
                    meta: { finexy: true },
                    props: route => ({
                        initTab: route.query['tab']
                    })
                },
                {
                    path: '/about',
                    component: FinexyWorkspacePage,
                    beforeEnter: checkLogin,
                    meta: { finexy: true }
                }
            ]
        },
        {
            path: '/login',
            component: LoginPage,
            beforeEnter: checkNotLogin
        },
        {
            path: '/signup',
            component: SignUpPage,
            beforeEnter: checkNotLogin
        },
        {
            path: '/verify_email',
            component: VerifyEmailPage,
            props: route => ({
                email: route.query['email'],
                token: route.query['token'],
                hasValidEmailVerifyToken: route.query['emailSent'] === 'true'
            })
        },
        {
            path: '/forgetpassword',
            component: ForgetPasswordPage,
            beforeEnter: checkNotLogin
        },
        {
            path: '/resetpassword',
            component: ResetPasswordPage,
            props: route => ({
                token: route.query['token']
            })
        },
        {
            path: '/oauth2_callback',
            component: OAuth2CallbackPage,
            props: route => ({
                token: route.query['token'],
                provider: route.query['provider'],
                platform: route.query['platform'],
                userName: route.query['userName'],
                errorCode: route.query['errorCode'],
                errorMessage: route.query['errorMessage']
            })
        },
        {
            path: '/unlock',
            component: UnlockPage,
            beforeEnter: checkLocked
        }
    ],
})

export default router;

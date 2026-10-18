package dev.dimension.flare.ui.presenter.home

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.paging.Pager
import androidx.paging.PagingData
import androidx.paging.cachedIn
import androidx.paging.compose.collectAsLazyPagingItems
import dev.dimension.flare.common.PagingState
import dev.dimension.flare.common.combineLatestFlowLists
import dev.dimension.flare.common.emptyFlow
import dev.dimension.flare.common.refreshSuspend
import dev.dimension.flare.common.toPagingState
import dev.dimension.flare.data.datasource.microblog.AuthenticatedMicroblogDataSource
import dev.dimension.flare.data.datasource.microblog.SearchSortMode
import dev.dimension.flare.data.datasource.microblog.searchStatusSorted
import dev.dimension.flare.data.datasource.microblog.paging.RemoteLoader
import dev.dimension.flare.data.datasource.microblog.paging.notSupported
import dev.dimension.flare.data.datasource.microblog.datasource.UserDataSource
import dev.dimension.flare.data.datasource.microblog.paging.toPagingSource
import dev.dimension.flare.data.datasource.microblog.pagingConfig
import dev.dimension.flare.data.repository.AccountRepository
import dev.dimension.flare.data.repository.accountServiceFlow
import dev.dimension.flare.data.repository.allAccountServicesFlow
import dev.dimension.flare.di.koinInject
import dev.dimension.flare.model.AccountType
import dev.dimension.flare.model.MicroBlogKey
import dev.dimension.flare.ui.model.UiProfile
import dev.dimension.flare.ui.model.UiState
import dev.dimension.flare.ui.model.UiTimelineV2
import dev.dimension.flare.ui.model.collectAsUiState
import dev.dimension.flare.ui.model.onSuccess
import dev.dimension.flare.ui.model.takeSuccess
import dev.dimension.flare.ui.model.toUi
import dev.dimension.flare.ui.presenter.PresenterBase
import dev.dimension.flare.web.shared.WebIgnore
import dev.dimension.flare.web.shared.WebPresenter
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map

@OptIn(ExperimentalCoroutinesApi::class)
@WebPresenter("search")
public class SearchPresenter(
    private val accountType: AccountType,
    private val initialQuery: String = "",
    private val federatedByDefault: Boolean = false,
) : PresenterBase<SearchState>() {
    private val accountRepository: AccountRepository by koinInject()

    private val accountsFlow by lazy {
        allAccountServicesFlow(accountRepository)
            .map {
                it
                    .filterIsInstance<UserDataSource>()
                    .filterIsInstance<AuthenticatedMicroblogDataSource>()
                    .toImmutableList()
            }.map {
                it.map { dataSource ->
                    val authenticated = dataSource as UserDataSource
                    val accountKey = dataSource.accountKey
                    authenticated.userHandler.userById(accountKey.id).toUi()
                }
            }.combineLatestFlowLists()
            .map {
                it
                    .mapNotNull {
                        it.takeSuccess()
                    }.toImmutableList()
            }
    }

    private val queryFlow by lazy {
        MutableStateFlow(initialQuery)
    }

    private val selectedAccountFlow by lazy {
        MutableStateFlow<UiProfile?>(null)
    }

    private val searchSortModeFlow = MutableStateFlow(SearchSortMode.Latest)

    private val selectedAccountTypeFlow: Flow<AccountType?> by lazy {
        selectedAccountFlow.map { profile ->
            resolveSearchAccountScope(accountType, profile?.key, federatedByDefault)
        }
    }

    private val usersFlow by lazy {
        selectedAccountTypeFlow.flatMapLatest { accountType ->
            if (accountType == null) {
                PagingData.emptyFlow(isError = false)
            } else {
                accountServiceFlow(accountType = accountType, repository = accountRepository).flatMapLatest { dataSource ->
                queryFlow.flatMapLatest { query ->
                    runCatching {
                        if (query.isEmpty()) {
                            PagingData.emptyFlow(isError = true)
                        } else {
                            Pager(config = pagingConfig) {
                                dataSource.searchUser(query).toPagingSource()
                            }.flow
                        }
                    }.getOrElse { PagingData.emptyFlow(isError = true) }
                }
            }
            }
        }
    }

    private fun statusFlow(scope: CoroutineScope) =
        selectedAccountTypeFlow.flatMapLatest { selectedType ->
            val loaders: Flow<RemoteLoader<UiTimelineV2>> =
                if (selectedType == null) {
                    combine(
                        allAccountServicesFlow(accountRepository),
                        queryFlow,
                        searchSortModeFlow,
                    ) { sources, query, order ->
                        if (query.isBlank()) {
                            notSupported()
                        } else {
                            FederatedSearchLoader(
                                services = sources,
                                query = query,
                                order = order,
                            )
                        }
                    }
                } else {
                    combine(
                        accountServiceFlow(accountType = selectedType, repository = accountRepository),
                        queryFlow,
                        searchSortModeFlow,
                    ) { source, query, order ->
                        if (query.isBlank()) {
                            notSupported()
                        } else {
                            source.searchStatusSorted(query, order)
                        }
                    }
                }
            TimelinePresenter(loader = loaders).createPager(scope)
        }

    @Composable
    override fun body(): SearchState {
        val scope = rememberCoroutineScope()
        val users =
            remember(usersFlow, scope) {
                usersFlow.cachedIn(scope)
            }.collectAsLazyPagingItems()
                .toPagingState()
        val status =
            remember {
                statusFlow(scope)
            }.collectAsLazyPagingItems()
                .toPagingState()
        val accounts by accountsFlow.collectAsUiState()
        val query by queryFlow.collectAsState()
        val selectedAccount by selectedAccountFlow.collectAsState()
        val sortMode by searchSortModeFlow.collectAsState()

        accounts.onSuccess {
            LaunchedEffect(it.size) {
                if (selectedAccount == null && !federatedByDefault) {
                    selectedAccountFlow.value =
                        if (accountType is AccountType.Specific) {
                            it.find { profile -> profile.key == accountType.accountKey }
                        } else {
                            null
                        }
                }
            }
        }

        return object : SearchState {
            override val users = users
            override val status = status
            override val searching = query.isNotEmpty()
            override val accounts = accounts
            override val selectedAccount = selectedAccount
            override val sortMode = sortMode

            override fun search(query: String) {
                queryFlow.value = query
            }

            override suspend fun refreshSuspend() {
                users.refreshSuspend()
                status.refreshSuspend()
            }

            override fun setAccount(profile: UiProfile) {
                selectedAccountFlow.value = profile
            }

            override fun setAllAccounts() {
                selectedAccountFlow.value = null
            }

            override fun setSortMode(mode: SearchSortMode) {
                searchSortModeFlow.value = mode
            }

            override fun setAccountKey(key: MicroBlogKey) {
                accounts.takeSuccess()?.firstOrNull { it.key == key }?.let {
                    selectedAccountFlow.value = it
                }
            }
        }
    }
}

/**
 * A regular search starts on all accounts, independent of Discover's active account.
 * A user-selected account is the only reason to scope results to one platform/account.
 */
internal fun resolveSearchAccountScope(
    initialAccountType: AccountType,
    selectedAccountKey: MicroBlogKey?,
    federatedByDefault: Boolean,
): AccountType? =
    selectedAccountKey?.let { AccountType.Specific(it) }
        ?: initialAccountType.takeUnless { federatedByDefault }

@Immutable
public interface SearchState {
    public val users: PagingState<UiProfile>
    public val status: PagingState<UiTimelineV2>
    public val searching: Boolean
    public val accounts: UiState<ImmutableList<UiProfile>>
    public val selectedAccount: UiProfile?
    public val sortMode: SearchSortMode

    @WebIgnore
    public fun setAllAccounts()

    @WebIgnore
    public fun setSortMode(mode: SearchSortMode)

    public fun search(query: String)

    public suspend fun refreshSuspend()

    @WebIgnore
    public fun setAccount(profile: UiProfile)

    public fun setAccountKey(key: MicroBlogKey)
}
